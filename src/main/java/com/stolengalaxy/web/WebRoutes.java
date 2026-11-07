package com.stolengalaxy.web;

import com.stolengalaxy.Invite;
import com.stolengalaxy.Project;
import com.stolengalaxy.Task;
import com.stolengalaxy.User;
import com.stolengalaxy.db.Database;
import com.stolengalaxy.smartcontract.ProjectContracts;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The website's form handlers and JSON API.
 * Forms POST here and are redirected back (to the page they came from, with {@code ?error=} on failure);
 * pages then load their data from {@code /api/*}.
 */
public final class WebRoutes {

    private static final String SESSION_USER = "userId";
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern GITHUB_USERNAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}");
    private static final Pattern GITHUB_REPO = Pattern.compile("https://github\\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+");

    private static ProjectContracts contracts;

    private WebRoutes() {
    }

    /** A form input problem: the user is sent back to {@code page} with {@code message} shown. */
    static final class FormException extends RuntimeException {
        final String page;

        FormException(String page, String message) {
            super(message);
            this.page = page;
        }
    }

    public static void register(Javalin app, ProjectContracts projectContracts) {
        contracts = projectContracts;
        app.exception(FormException.class, (e, ctx) -> {
            String separator = e.page.contains("?") ? "&" : "?";
            ctx.redirect(e.page + separator + "error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8),
                    HttpStatus.SEE_OTHER);
        });

        // Logged-in pages
        app.before("/workspace.html", ctx -> {
            if (currentUserId(ctx) == null) {
                ctx.redirect("/login.html", HttpStatus.SEE_OTHER);
                ctx.skipRemainingHandlers();
            }
        });
        app.before("/api/*", ctx -> {
            if (!ctx.path().equals("/api/health") && currentUserId(ctx) == null) {
                throw new UnauthorizedResponse("Not logged in");
            }
        });

        app.get("/login", ctx -> ctx.redirect("/login.html"));
        app.get("/signup", ctx -> ctx.redirect("/signup.html"));
        app.get("/workspace", ctx -> ctx.redirect("/workspace.html"));

        app.post("/signup", WebRoutes::signup);
        app.post("/login", WebRoutes::login);
        app.get("/logout", WebRoutes::logout);

        app.post("/projects", WebRoutes::createProject);
        app.post("/tasks", WebRoutes::addTask);
        app.post("/stake", WebRoutes::stake);
        app.post("/invites", WebRoutes::invite);
        app.post("/tasks/weights", WebRoutes::updateWeights);
        app.post("/invites/respond", WebRoutes::respondToInvite);
        app.post("/invites/cancel", WebRoutes::cancelInvite);
        app.post("/contract", WebRoutes::createContract);

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
        app.get("/api/me", WebRoutes::me);
        app.get("/api/projects/{id}", WebRoutes::project);
    }

    // ===== Accounts =====

    private static void signup(Context ctx) {
        String page = "/signup.html";
        String name = required(ctx, "name", 100, page, "Full name");
        String email = email(ctx, page);
        String password = ctx.formParam("password");
        if (password == null || password.length() < 8 || !password.matches(".*[0-9].*")) {
            throw new FormException(page, "Use at least 8 characters for your password, including a number.");
        }
        String github = required(ctx, "github", 40, page, "GitHub username").replaceFirst("^@", "");
        if (!GITHUB_USERNAME.matcher(github).matches()) {
            throw new FormException(page, "That isn't a valid GitHub username.");
        }

        int userId = Database.transaction(em -> {
            if (findUserByEmail(em, email) != null) {
                throw new FormException(page, "An account with that email already exists. Try logging in.");
            }
            User user = new User(name, email, Passwords.hash(password), github);
            em.persist(user);
            return user.userID();
        });
        startSession(ctx, userId);
        ctx.redirect("/workspace.html", HttpStatus.SEE_OTHER);
    }

    private static void login(Context ctx) {
        String page = "/login.html";
        String email = email(ctx, page);
        String password = ctx.formParam("password");
        User user = Database.read(em -> findUserByEmail(em, email));
        if (user == null || password == null || !Passwords.verify(password, user.passwordHash())) {
            throw new FormException(page, "Incorrect email or password.");
        }
        startSession(ctx, user.userID());
        ctx.redirect("/workspace.html", HttpStatus.SEE_OTHER);
    }

    private static void logout(Context ctx) {
        HttpSession session = ctx.req().getSession(false);
        if (session != null) {
            session.invalidate();
        }
        ctx.redirect("/login.html", HttpStatus.SEE_OTHER);
    }

    // ===== Workspace forms =====

    private static void createProject(Context ctx) {
        int userId = requireUserId(ctx);
        String page = "/workspace.html";
        String name = required(ctx, "name", 70, page, "Project name");
        String description = required(ctx, "description", 3000, page, "Description");
        String repo = required(ctx, "repo", 200, page, "GitHub repository").replaceFirst("/+$", "");
        if (!GITHUB_REPO.matcher(repo).matches()) {
            throw new FormException(page, "The repository must look like https://github.com/your-team/project.");
        }
        LocalDateTime deadline;
        try {
            deadline = LocalDateTime.parse(required(ctx, "deadline", 30, page, "Deadline"));
        } catch (DateTimeParseException e) {
            throw new FormException(page, "That deadline isn't a valid date and time.");
        }
        if (!deadline.isAfter(LocalDateTime.now())) {
            throw new FormException(page, "The deadline must be in the future.");
        }
        BigDecimal stake;
        try {
            stake = new BigDecimal(required(ctx, "stake", 30, page, "Stake per member"));
        } catch (NumberFormatException e) {
            throw new FormException(page, "The stake must be a number.");
        }
        if (stake.signum() <= 0 || stake.stripTrailingZeros().scale() > 6) {
            throw new FormException(page, "The stake must be more than 0, with at most 6 decimal places (USDC).");
        }

        int projectId = Database.transaction(em -> {
            User user = em.find(User.class, userId);
            Project project = new Project(name, description, repo, deadline, stake, user);
            em.persist(project);
            return project.projectID();
        });
        ctx.redirect(projectPage(projectId), HttpStatus.SEE_OTHER);
    }

    private static void addTask(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = intParam(ctx, "projectId", "/workspace.html");
        String page = projectPage(projectId);
        String title = required(ctx, "title", 90, page, "Task");
        int weight = weight(ctx, "weight", page);
        int memberId = intParam(ctx, "member", page);

        Database.transaction(em -> {
            Project project = unlockedProject(em, projectId, userId, page);
            User member = em.find(User.class, memberId);
            if (member == null || !project.isMember(member)) {
                throw new FormException(page, "Tasks can only be assigned to project members.");
            }
            if (project.taskFor(member).isPresent()) {
                throw new FormException(page, member.username() + " already has a task in this project.");
            }
            checkWeightFits(project, weight, page);
            project.assignTask(member, title, weight);
            return null;
        });
        ctx.redirect(page, HttpStatus.SEE_OTHER);
    }

    /**
     * Records that the member commits their stake. The money moves on-chain when the creator creates the
     * smart contract (see {@link ProjectContracts#create}).
     */
    private static void stake(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = intParam(ctx, "projectId", "/workspace.html");
        String page = projectPage(projectId);

        Database.transaction(em -> {
            Project project = unlockedProject(em, projectId, userId, page);
            User user = em.find(User.class, userId);
            if (project.hasStaked(user)) {
                throw new FormException(page, "You've already staked in this project.");
            }
            if (project.deadline().isBefore(LocalDateTime.now())) {
                throw new FormException(page, "The deadline has passed, so stakes are closed.");
            }
            project.recordStake(user);
            return null;
        });
        ctx.redirect(page, HttpStatus.SEE_OTHER);
    }

    private static void invite(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = intParam(ctx, "projectId", "/workspace.html");
        String page = projectPage(projectId);
        String email = email(ctx, page);
        String task = required(ctx, "task", 90, page, "Task");
        int weight = weight(ctx, "weight", page);

        Database.transaction(em -> {
            Project project = unlockedProject(em, projectId, userId, page);
            User inviter = em.find(User.class, userId);
            boolean alreadyMember = project.users().stream().anyMatch(u -> email.equals(u.email()));
            if (alreadyMember) {
                throw new FormException(page, email + " is already a member of this project.");
            }
            boolean alreadyInvited = project.invites().stream()
                    .anyMatch(i -> i.email().equals(email) && i.status() == Invite.Status.PENDING);
            if (alreadyInvited) {
                throw new FormException(page, email + " already has a pending invitation.");
            }
            checkWeightFits(project, weight, page);
            project.invite(inviter, email, task, weight);
            return null;
        });
        ctx.redirect(page, HttpStatus.SEE_OTHER);
    }

    /** Sets every task's weight at once, from fields named {@code weight-<taskId>}. */
    private static void updateWeights(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = intParam(ctx, "projectId", "/workspace.html");
        String page = projectPage(projectId);

        Database.transaction(em -> {
            Project project = unlockedProject(em, projectId, userId, page);
            Map<Task, Integer> weights = new LinkedHashMap<>();
            for (Task task : project.tasks()) {
                weights.put(task, weight(ctx, "weight-" + task.taskID(), page));
            }
            int total = weights.values().stream().mapToInt(Integer::intValue).sum() + project.reservedWeight();
            if (total > 100) {
                throw new FormException(page, "Weights would total " + total + "%"
                        + (project.reservedWeight() > 0 ? " (including " + project.reservedWeight() + "% held by pending invitations)" : "")
                        + ". The total can't be more than 100%.");
            }
            weights.forEach(Task::setWeightPercentage);
            return null;
        });
        ctx.redirect(page, HttpStatus.SEE_OTHER);
    }

    /**
     * Creates the project's pool on-chain and deposits every member's stake into it. Only the creator can do this,
     * once every member has a task, every member has staked and the task weights total 100%.
     */
    private static void createContract(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = intParam(ctx, "projectId", "/workspace.html");
        String page = projectPage(projectId);

        Database.read(em -> memberProject(em, projectId, userId)); // members only; the rest is checked by create()
        try {
            contracts.create(projectId, userId);
        } catch (ProjectContracts.CreationException e) {
            throw new FormException(page, e.getMessage());
        }
        ctx.redirect(page + "&notice=" + URLEncoder.encode(
                "Smart contract created. Every member's stake is now held in the pool until the deadline.",
                StandardCharsets.UTF_8), HttpStatus.SEE_OTHER);
    }

    /** Withdraws a pending invite. Allowed for whoever sent it and for the project's creator. */
    private static void cancelInvite(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = intParam(ctx, "projectId", "/workspace.html");
        String page = projectPage(projectId);
        int inviteId = intParam(ctx, "inviteId", page);

        Database.transaction(em -> {
            Project project = memberProject(em, projectId, userId);
            User user = em.find(User.class, userId);
            Invite invite = em.find(Invite.class, inviteId);
            if (invite == null || invite.project() != project || invite.status() != Invite.Status.PENDING) {
                throw new FormException(page, "That invitation has already been answered or cancelled.");
            }
            if (!invite.canBeCancelledBy(user)) {
                throw new FormException(page, "Only the person who sent an invitation, or the project's creator, can cancel it.");
            }
            invite.cancel();
            return null;
        });
        ctx.redirect(page, HttpStatus.SEE_OTHER);
    }

    private static void respondToInvite(Context ctx) {
        int userId = requireUserId(ctx);
        String page = "/workspace.html";
        int inviteId = intParam(ctx, "inviteId", page);
        String response = ctx.formParam("response");
        if (!"accepted".equals(response) && !"declined".equals(response)) {
            throw new FormException(page, "Choose accept or decline.");
        }

        int projectId = Database.transaction(em -> {
            User user = em.find(User.class, userId);
            Invite invite = em.find(Invite.class, inviteId);
            if (invite == null || !invite.email().equals(user.email()) || invite.status() != Invite.Status.PENDING) {
                throw new FormException(page, "That invitation is no longer available.");
            }
            if (response.equals("declined")) {
                invite.decline();
                return -1;
            }
            if (invite.project().isLocked()) {
                throw new FormException(page, "That project's smart contract has already been created, so it can't take new members.");
            }
            if (invite.project().taskFor(user).isPresent()) {
                throw new FormException(page, "You already have a task in that project.");
            }
            invite.accept(user);
            return invite.project().projectID();
        });
        ctx.redirect(projectId < 0 ? page : projectPage(projectId), HttpStatus.SEE_OTHER);
    }

    // ===== JSON API =====

    private static void me(Context ctx) {
        int userId = requireUserId(ctx);
        ctx.json(Database.read(em -> {
            User user = em.find(User.class, userId);
            if (user == null) {
                throw new UnauthorizedResponse("Account no longer exists");
            }
            List<Invite> invites = em.createQuery(
                            "SELECT i FROM Invite i WHERE i.email = :email AND i.status = :status ORDER BY i.inviteID",
                            Invite.class)
                    .setParameter("email", user.email())
                    .setParameter("status", Invite.Status.PENDING)
                    .getResultList();
            return Dtos.MeDto.of(user, invites);
        }));
    }

    private static void project(Context ctx) {
        int userId = requireUserId(ctx);
        int projectId = ctx.pathParamAsClass("id", Integer.class).get();
        ctx.json(Database.read(em -> {
            Project project = em.find(Project.class, projectId);
            User user = em.find(User.class, userId);
            if (project == null) {
                throw new NotFoundResponse("Project not found");
            }
            if (!project.isMember(user)) {
                throw new ForbiddenResponse("You aren't a member of this project");
            }
            return Dtos.ProjectDto.of(project, user, Dtos.ContractDto.of(project, contracts));
        }));
    }

    // ===== Helpers =====

    private static void startSession(Context ctx, int userId) {
        ctx.req().getSession(true);
        ctx.req().changeSessionId(); // new ID on login, against session fixation
        ctx.sessionAttribute(SESSION_USER, userId);
    }

    private static Integer currentUserId(Context ctx) {
        return ctx.sessionAttribute(SESSION_USER);
    }

    private static int requireUserId(Context ctx) {
        Integer userId = currentUserId(ctx);
        if (userId == null) {
            throw new FormException("/login.html", "Please log in first.");
        }
        return userId;
    }

    private static Project memberProject(EntityManager em, int projectId, int userId) {
        Project project = em.find(Project.class, projectId);
        if (project == null || !project.isMember(em.find(User.class, userId))) {
            throw new FormException("/workspace.html", "Project not found.");
        }
        return project;
    }

    /** Like {@link #memberProject}, but refuses changes once the project's contract has been created. */
    private static Project unlockedProject(EntityManager em, int projectId, int userId, String page) {
        Project project = memberProject(em, projectId, userId);
        if (project.isLocked()) {
            throw new FormException(page, (project.contractStatus() == Project.ContractStatus.CREATED
                    ? "The smart contract has been created" : "Creating the smart contract has started")
                    + ", so tasks, weights, members and stakes can't change any more.");
        }
        return project;
    }

    private static User findUserByEmail(EntityManager em, String email) {
        return em.createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
                .setParameter("email", email)
                .getResultStream().findFirst().orElse(null);
    }

    private static String projectPage(int projectId) {
        return "/workspace.html?project=" + projectId;
    }

    private static String email(Context ctx, String page) {
        String email = required(ctx, "email", 254, page, "Email address").toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw new FormException(page, "That isn't a valid email address.");
        }
        return email;
    }

    private static String required(Context ctx, String field, int maxLength, String page, String label) {
        String value = optional(ctx, field, maxLength, page, label);
        if (value == null) {
            throw new FormException(page, label + " is required.");
        }
        return value;
    }

    /** The trimmed value, or null if missing or blank. */
    private static String optional(Context ctx, String field, int maxLength, String page, String label) {
        String value = ctx.formParam(field);
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();
        if (value.length() > maxLength) {
            throw new FormException(page, label + " must be at most " + maxLength + " characters.");
        }
        return value;
    }

    /** A task weight from the form: a whole number of percent, 1-100. */
    private static int weight(Context ctx, String field, String page) {
        try {
            int weight = Integer.parseInt(ctx.formParam(field).trim());
            if (weight >= 1 && weight <= 100) {
                return weight;
            }
        } catch (NullPointerException | NumberFormatException e) {
            // fall through
        }
        throw new FormException(page, "Each weight must be a whole number from 1 to 100.");
    }

    /** Task weights plus weights held by pending invites can't go over 100%. */
    private static void checkWeightFits(Project project, int weight, String page) {
        int free = 100 - project.assignedWeight() - project.reservedWeight();
        if (weight > free) {
            throw new FormException(page, "Only " + free + "% of the project's weight is left. "
                    + "Lower this task's weight, or rebalance the others with Edit weights.");
        }
    }

    private static int intParam(Context ctx, String field, String page) {
        try {
            return Integer.parseInt(ctx.formParam(field));
        } catch (NumberFormatException e) {
            throw new FormException(page, "Something went wrong with that form. Please reload and try again.");
        }
    }
}
