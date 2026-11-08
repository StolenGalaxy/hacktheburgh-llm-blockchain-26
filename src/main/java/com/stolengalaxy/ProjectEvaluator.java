package com.stolengalaxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stolengalaxy.db.Database;
import com.stolengalaxy.model.Gemini;
import com.stolengalaxy.smartcontract.ProjectContracts;
import com.stolengalaxy.smartcontract.Wallets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Evaluates a project after its deadline and pays out its pool:
 * <ol>
 *     <li>{@link Gemini} scores each member's commits against their task,</li>
 *     <li>{@link EvaluationParser} turns the reply into {@link Evaluation}s,</li>
 *     <li>{@link PayoutAlgorithm} works out each member's share of the pool,</li>
 *     <li>the scores and payouts are saved (see {@link ProjectEvaluation}),</li>
 *     <li>the pool is settled on-chain and each share sent to its member's wallet ({@link ProjectContracts#payOut}).</li>
 * </ol>
 * Any member can request it. It runs in the background (Gemini can take a minute), and the page polls for the result.
 */
public final class ProjectEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectEvaluator.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Payouts may differ from the pool by float rounding, but by more than this means the algorithm didn't share out the pool. */
    private static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.01");

    /** Gets Gemini's reply ({"evaluations": [...]}) for a project. Swappable so the flow can be tested without Gemini. */
    @FunctionalInterface
    public interface Scorer {
        JsonObject score(Project project) throws Exception;
    }

    /** A user-facing reason the evaluation didn't happen (or didn't finish). */
    public static final class EvaluationException extends RuntimeException {
        public EvaluationException(String message) {
            super(message);
        }
    }

    private final ProjectContracts contracts;
    private final Scorer scorer;
    /** Projects being evaluated right now. A RUNNING evaluation that isn't in here was cut off by a restart. */
    private final Set<Integer> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "evaluator");
        thread.setDaemon(true);
        return thread;
    });

    /** Scores with Gemini (needs the GEMINI_API_KEY environment variable). */
    public ProjectEvaluator(ProjectContracts contracts) {
        this(contracts, ProjectEvaluator::askGemini);
    }

    public ProjectEvaluator(ProjectContracts contracts, Scorer scorer) {
        this.contracts = contracts;
        this.scorer = scorer;
    }

    /** Whether the project's evaluation is running in this server right now. */
    public boolean isRunning(int projectId) {
        return running.contains(projectId);
    }

    /**
     * Starts evaluating the project in the background, after checking it can be done. Returns straight away.
     *
     * @throws EvaluationException with a message to show the user if it can't be started
     */
    public void request(int projectId, int userId) {
        if (!contracts.available()) {
            throw new EvaluationException("The blockchain isn't running, so the pool can't be paid out: "
                    + contracts.unavailableReason() + ".");
        }
        if (!running.add(projectId)) {
            throw new EvaluationException("This project is already being evaluated. The results will appear here when it's done.");
        }
        try {
            Database.transaction(em -> {
                Project project = em.find(Project.class, projectId);
                User user = em.find(User.class, userId);
                if (project == null || !project.isMember(user)) {
                    throw new EvaluationException("Project not found.");
                }
                String blocker = blocker(project);
                if (blocker != null) {
                    throw new EvaluationException(blocker);
                }
                checkPoolClosed(project.poolId());
                ProjectEvaluation evaluation = project.evaluation();
                if (evaluation == null) {
                    evaluation = new ProjectEvaluation(project);
                    em.persist(evaluation);
                }
                evaluation.start(user);
                return null;
            });
            worker.submit(() -> run(projectId));
        } catch (RuntimeException e) {
            running.remove(projectId);
            throw e;
        }
    }

    /**
     * Why evaluation can't be requested for this project (apart from the deadline, which the page counts down to),
     * or null if it can.
     */
    public static String blocker(Project project) {
        if (!project.deadline().isBefore(LocalDateTime.now())) {
            return "Evaluation can be requested once the deadline has passed.";
        }
        return blockerOtherThanDeadline(project);
    }

    /** Like {@link #blocker}, ignoring the deadline, for the page to know whether the button works once it's passed. */
    public static String blockerOtherThanDeadline(Project project) {
        if (project.contractStatus() != Project.ContractStatus.CREATED) {
            return "The smart contract was never created, so there's no pool to evaluate and pay out.";
        }
        ProjectEvaluation evaluation = project.evaluation();
        if (evaluation != null && evaluation.status() == ProjectEvaluation.Status.PAID) {
            return "This project has already been evaluated and paid out.";
        }
        return null;
    }

    /** The contract only allows settling once the pool's own timer (set a second or so after the deadline) has run out. */
    private void checkPoolClosed(long poolId) {
        ProjectContracts.PoolState pool = contracts.poolState(poolId);
        if (pool == null) {
            throw new EvaluationException("Couldn't read the project's pool from the blockchain. Try again.");
        }
        if (!pool.exists()) {
            throw new EvaluationException("This project's pool isn't on the current blockchain (its state was probably deleted), so it can't be paid out.");
        }
        if (!Instant.now().isAfter(pool.depositsCloseAt().plusSeconds(1))) {
            throw new EvaluationException("The pool on the blockchain closes at "
                    + TIME.format(pool.depositsCloseAt().atZone(ZoneId.systemDefault()))
                    + ", just after the deadline. Try again in a few seconds.");
        }
    }

    // ===== Background work =====

    private void run(int projectId) {
        try {
            Map<Integer, BigDecimal> payouts = Database.read(em -> savedPayouts(em.find(Project.class, projectId)));
            if (payouts == null) {
                payouts = score(projectId);
            } else {
                LOG.info("Project {}: reusing the saved scores to finish paying out", projectId);
            }
            long poolId = Database.read(em -> em.find(Project.class, projectId).poolId());
            contracts.payOut(poolId, payouts);
            finish(projectId, null);
            LOG.info("Project {}: evaluated and paid out {}", projectId, payouts);
        } catch (EvaluationException | ProjectContracts.PayoutException e) {
            LOG.warn("Project {}: evaluation failed: {}", projectId, e.getMessage());
            finish(projectId, e.getMessage());
        } catch (Throwable e) {
            LOG.error("Project {}: evaluation failed", projectId, e);
            finish(projectId, "Something went wrong: " + firstLine(e) + ". Request evaluation again to retry.");
        } finally {
            running.remove(projectId);
        }
    }

    /** The payouts saved by an earlier attempt that got as far as scoring, or null if there are none. */
    private static Map<Integer, BigDecimal> savedPayouts(Project project) {
        ProjectEvaluation evaluation = project.evaluation();
        if (evaluation == null || !evaluation.hasScores()) {
            return null;
        }
        Map<Integer, BigDecimal> payouts = new LinkedHashMap<>();
        evaluation.results().forEach(result -> payouts.put(result.user().userID(), result.payout()));
        return payouts;
    }

    /** Steps 1-4: Gemini's scores, parsed and checked, turned into payouts, and saved. Returns the payouts. */
    private Map<Integer, BigDecimal> score(int projectId) {
        // Load everything Gemini and the payout algorithm read, so the entities work after the EntityManager closes
        Project project = Database.read(em -> {
            Project p = em.find(Project.class, projectId);
            p.tasks().forEach(task -> task.user().githubUsername());
            p.users().size();
            return p;
        });

        JsonObject reply;
        try {
            reply = scorer.score(project);
        } catch (EvaluationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvaluationException("Evaluation was interrupted. Request it again.");
        } catch (Exception e) {
            LOG.error("Project {}: asking Gemini failed", projectId, e);
            throw new EvaluationException("Couldn't get scores from Gemini: " + firstLine(e) + ". Request evaluation again to retry.");
        }

        List<Evaluation> evaluations;
        try {
            JsonArray array = reply == null || !reply.has("evaluations") ? null : reply.getAsJsonArray("evaluations");
            evaluations = EvaluationParser.parse(array);
        } catch (RuntimeException e) {
            throw new EvaluationException("Gemini's reply couldn't be used (" + firstLine(e) + "). Request evaluation again to retry.");
        }
        checkEveryoneScored(project, evaluations);

        BigDecimal pool = project.stakeAmount().multiply(BigDecimal.valueOf(project.tasks().size()));
        HashMap<Integer, Double> raw = PayoutAlgorithm.calculatePayout(evaluations, project);
        Map<Integer, BigDecimal> payouts = exactPayouts(raw, pool);

        Map<Integer, Evaluation> byUser = new HashMap<>();
        evaluations.forEach(evaluation -> byUser.put(evaluation.userId(), evaluation));
        Database.transaction(em -> {
            Project managed = em.find(Project.class, projectId);
            ProjectEvaluation evaluation = managed.evaluation();
            for (Task task : managed.tasks()) {
                int userId = task.user().userID();
                Evaluation scored = byUser.get(userId);
                evaluation.addResult(new MemberResult(evaluation, task.user(), scored.score(), scored.feedback(),
                        scored.evidence(), task.weightPercentage(), payouts.get(userId)));
            }
            return null;
        });
        LOG.info("Project {}: scored {}", projectId, evaluations);
        return payouts;
    }

    /** Gemini must score every member exactly once, and nobody else. */
    private static void checkEveryoneScored(Project project, List<Evaluation> evaluations) {
        Map<Integer, String> members = new LinkedHashMap<>();
        project.tasks().forEach(task -> members.put(task.user().userID(), task.user().username()));

        Set<Integer> seen = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (Evaluation evaluation : evaluations) {
            if (!members.containsKey(evaluation.userId())) {
                problems.add("it scored user " + evaluation.userId() + ", who isn't in the project");
            } else if (!seen.add(evaluation.userId())) {
                problems.add("it scored " + members.get(evaluation.userId()) + " twice");
            }
        }
        members.forEach((id, name) -> {
            if (!seen.contains(id)) {
                problems.add("it didn't score " + name);
            }
        });
        if (!problems.isEmpty()) {
            throw new EvaluationException("Gemini's reply didn't match the team: " + String.join("; ", problems)
                    + ". Request evaluation again to retry.");
        }
    }

    /**
     * Turns the payout algorithm's doubles into exact USDC amounts (6 decimal places) that add up to exactly what the
     * pool holds, as the contract requires. Rounding leftovers (fractions of a cent) go to the largest payout.
     */
    static Map<Integer, BigDecimal> exactPayouts(Map<Integer, Double> raw, BigDecimal pool) {
        Map<Integer, BigDecimal> exact = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> entry : raw.entrySet()) {
            double value = entry.getValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < -0.000001) {
                throw new EvaluationException("The payout algorithm gave user " + entry.getKey() + " an invalid payout (" + value + ").");
            }
            exact.put(entry.getKey(), BigDecimal.valueOf(Math.max(0, value)).setScale(6, RoundingMode.DOWN));
        }
        BigDecimal total = exact.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal leftover = pool.subtract(total);
        if (leftover.abs().compareTo(ROUNDING_TOLERANCE) > 0) {
            throw new EvaluationException("The payout algorithm shared out " + Wallets.dollars(total) + ", but the pool holds "
                    + Wallets.dollars(pool) + ".");
        }
        Integer largest = exact.entrySet().stream().max(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .orElseThrow().getKey();
        exact.put(largest, exact.get(largest).add(leftover));
        return exact;
    }

    private static void finish(int projectId, String error) {
        try {
            Database.transaction(em -> {
                em.find(Project.class, projectId).evaluation().finish(error);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Project {}: couldn't save the evaluation's outcome", projectId, e);
        }
    }

    private static JsonObject askGemini(Project project) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new EvaluationException("GEMINI_API_KEY isn't set on the server, so Gemini can't be asked. "
                    + "Set it in the run configuration's environment variables, restart, and request evaluation again.");
        }
        return Gemini.query(project.projectDescription(), project.tasks(), project.repoURL());
    }

    private static String firstLine(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.lines().findFirst().orElse("");
    }
}
