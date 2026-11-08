package com.stolengalaxy;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Entity
public class Project {

    /** Where the project's on-chain pool is up to. */
    public enum ContractStatus {
        /** Not created yet: tasks, weights, members and stakes can still change. */
        NONE,
        /** Creation started but didn't finish (e.g. the chain went down); creating it again picks up where it left off. */
        CREATING,
        /** The pool exists and holds every member's stake. */
        CREATED
    }

    @Id
    @GeneratedValue
    private int projectID;

    @Column(nullable = false)
    private String name;

    @Lob
    @Column(nullable = false)
    private String projectDescription;

    private String repoURL;

    /** Deadline as entered by the creator (no time zone). */
    @Column(nullable = false)
    private LocalDateTime deadline;

    /** Stake each member deposits, in USDC. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal stakeAmount;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** Members of the project. This side owns the many-to-many join table. */
    @ManyToMany
    @JoinTable(name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private List<User> users = new ArrayList<>();

    /** Members who have deposited their stake. */
    @ManyToMany
    @JoinTable(name = "project_stakes",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> stakedUsers = new LinkedHashSet<>();

    /** One task per member; tasks are saved and deleted together with the project. */
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("taskID")
    private List<Task> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Invite> invites = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus contractStatus = ContractStatus.NONE;

    /** The pool's ID in the ProjectDeposit contract (a uint256 on-chain). Random, so it never clashes across database resets. */
    private Long poolId;

    /** Address of the ProjectDeposit contract holding the pool. */
    private String contractAddress;

    private LocalDateTime contractCreatedAt;

    /**
     * The AI evaluation and payout, once a member has requested it. The owning side is
     * {@link ProjectEvaluation#project()}, so this adds no column to the project table.
     */
    @OneToOne(mappedBy = "project")
    private ProjectEvaluation evaluation;

    protected Project() {
        // for JPA
    }

    /** Creates a project with its creator as the first member. */
    public Project(String name, String projectDescription, String repoURL,
                   LocalDateTime deadline, BigDecimal stakeAmount, User createdBy) {
        this.name = name;
        this.projectDescription = projectDescription;
        this.repoURL = repoURL;
        this.deadline = deadline;
        this.stakeAmount = stakeAmount;
        this.createdBy = createdBy;
        addUser(createdBy);
    }

    /** Adds a member to the project, keeping both sides of the relationship in sync. */
    public void addUser(User user) {
        if (!users.contains(user)) {
            users.add(user);
            user.projects().add(this);
        }
    }

    /** Removes a member, their task and their stake record. Only before the contract exists, since it holds their stake. */
    public void removeUser(User user) {
        requireUnlocked();
        users.remove(user);
        user.projects().remove(this);
        stakedUsers.remove(user);
        taskFor(user).ifPresent(task -> {
            tasks.remove(task);
            user.tasks().remove(task);
        });
    }

    /**
     * Unlinks the project from its members before it's deleted with {@code em.remove}. The tasks, invites and
     * membership/stake rows go with the project; this updates the users' side so cached users don't keep it.
     * Only before the contract exists: after that, the pool on-chain holds the members' stakes.
     */
    public void prepareForDelete() {
        requireUnlocked();
        for (User user : users) {
            user.projects().remove(this);
            user.tasks().removeAll(tasks);
        }
    }

    /** Assigns a task to a user, adding them as a member if they aren't one already. */
    public Task assignTask(User user, String objective, int weightPercentage) {
        requireUnlocked();
        if (taskFor(user).isPresent()) {
            throw new IllegalStateException(user.username() + " already has a task in this project");
        }
        addUser(user);
        Task task = new Task(this, user, objective, weightPercentage);
        tasks.add(task);
        user.tasks().add(task);
        return task;
    }

    public Invite invite(User invitedBy, String email, String objective, int weightPercentage) {
        requireUnlocked();
        Invite invite = new Invite(this, invitedBy, email, objective, weightPercentage);
        invites.add(invite);
        return invite;
    }

    public void recordStake(User user) {
        requireUnlocked();
        if (!users.contains(user)) {
            throw new IllegalStateException(user.username() + " is not a member of this project");
        }
        stakedUsers.add(user);
    }

    /** Sum of task weights. Must be exactly 100 before the contract is created. */
    public int assignedWeight() {
        return tasks.stream().mapToInt(Task::weightPercentage).sum();
    }

    /** Weight held by pending invites; it becomes task weight when they're accepted. */
    public int reservedWeight() {
        return invites.stream()
                .filter(invite -> invite.status() == Invite.Status.PENDING)
                .mapToInt(Invite::weightPercentage).sum();
    }

    /**
     * What still stops the smart contract being created; empty when it's ready.
     * Ready means every member has a task, every member has staked, and task weights total 100%.
     */
    public List<String> contractBlockers() {
        List<String> blockers = new ArrayList<>();
        if (deadline.isBefore(LocalDateTime.now())) {
            blockers.add("The deadline has passed");
        }
        if (stakeAmount.signum() <= 0) {
            blockers.add("The stake per member must be more than 0");
        }
        for (User user : users) {
            if (taskFor(user).isEmpty()) {
                blockers.add(user.username() + " has no task");
            }
            if (!hasStaked(user)) {
                blockers.add(user.username() + " hasn't staked");
            }
        }
        if (assignedWeight() != 100) {
            blockers.add("Task weights total " + assignedWeight() + "%, not 100%");
        }
        if (reservedWeight() > 0) {
            blockers.add("Pending invitations need a response");
        }
        return blockers;
    }

    /** Marks contract creation as started, keeping the pool ID from an earlier attempt if there was one. */
    public void beginContract(long newPoolId) {
        if (contractStatus == ContractStatus.CREATED) {
            throw new IllegalStateException("The contract has already been created");
        }
        if (poolId == null) {
            poolId = newPoolId;
        }
        contractStatus = ContractStatus.CREATING;
    }

    public void markContractCreated(String contractAddress) {
        if (contractStatus != ContractStatus.CREATING) {
            throw new IllegalStateException("Contract creation wasn't started");
        }
        this.contractStatus = ContractStatus.CREATED;
        this.contractAddress = contractAddress;
        this.contractCreatedAt = LocalDateTime.now();
    }

    /** Once contract creation has started, tasks, weights, members and stakes are fixed. */
    public boolean isLocked() {
        return contractStatus != ContractStatus.NONE;
    }

    private void requireUnlocked() {
        if (isLocked()) {
            throw new IllegalStateException("The project's smart contract has been created, so it can't be changed");
        }
    }

    public boolean isMember(User user) { return users.contains(user); }
    public boolean hasStaked(User user) { return stakedUsers.contains(user); }

    public Optional<Task> taskFor(User user) {
        return tasks.stream().filter(task -> task.user() == user).findFirst();
    }

    public int projectID() { return projectID; }
    public String name() { return name; }
    public String projectDescription() { return projectDescription; }
    public String repoURL() { return repoURL; }
    public LocalDateTime deadline() { return deadline; }
    public BigDecimal stakeAmount() { return stakeAmount; }
    public User createdBy() { return createdBy; }
    public List<User> users() { return users; }
    public Set<User> stakedUsers() { return stakedUsers; }
    public List<Task> tasks() { return tasks; }
    public List<Invite> invites() { return invites; }
    public ContractStatus contractStatus() { return contractStatus; }
    public Long poolId() { return poolId; }
    public String contractAddress() { return contractAddress; }
    public LocalDateTime contractCreatedAt() { return contractCreatedAt; }
    /** Null until evaluation has been requested. */
    public ProjectEvaluation evaluation() { return evaluation; }

    void setEvaluation(ProjectEvaluation evaluation) { this.evaluation = evaluation; }

    public void setName(String name) { this.name = name; }
    public void setProjectDescription(String projectDescription) { this.projectDescription = projectDescription; }
    public void setRepoURL(String repoURL) { this.repoURL = repoURL; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }

    @Override
    public String toString() {
        return "Project[projectID=" + projectID + ", name=" + name + "]";
    }
}
