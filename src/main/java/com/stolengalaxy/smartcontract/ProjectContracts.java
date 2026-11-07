package com.stolengalaxy.smartcontract;

import com.stolengalaxy.Project;
import com.stolengalaxy.User;
import com.stolengalaxy.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Creates each project's pool in the ProjectDeposit contract and deposits every member's stake into it.
 *
 * <p>Members don't have wallets of their own yet: the {@link Bank} holds a wallet per user, and because the chain is
 * a local demo one, it mints each member's stake in mock USDC right before depositing it ("No real funds").</p>
 *
 * <p>Creation is safe to retry. The pool ID is saved before anything happens on-chain, and each step checks the
 * chain first (does the pool exist? how much has this wallet deposited?), so running it again only does what's missing.</p>
 */
public final class ProjectContracts implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectContracts.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Bank bank; // null when the chain couldn't start
    private final String unavailableReason;
    /** One creation at a time, so two clicks can't both deposit. Creating a contract is rare, so one lock is enough. */
    private final ReentrantLock creationLock = new ReentrantLock();

    private ProjectContracts(Bank bank, String unavailableReason) {
        this.bank = bank;
        this.unavailableReason = unavailableReason;
    }

    /** Starts the persistent local chain in {@code stateDir}. If it can't start, the site still runs without contracts. */
    public static ProjectContracts start(Path stateDir) {
        LOG.info("Starting local blockchain (state in {})", stateDir.toAbsolutePath());
        try {
            Bank bank = Bank.persistent(stateDir);
            LOG.info("Blockchain ready: ProjectDeposit at {}, mock USDC at {}", bank.poolContractAddress(), bank.usdcContractAddress());
            return new ProjectContracts(bank, null);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            if (reason.startsWith("Cannot run program")) {
                reason = "Foundry isn't installed (install it from https://getfoundry.sh, then run foundryup)";
            }
            LOG.error("Blockchain unavailable, so smart contracts can't be created: {}", reason, e);
            return new ProjectContracts(null, reason);
        }
    }

    public boolean available() {
        return bank != null && bank.isRunning();
    }

    /** Why the chain isn't available, or null if it is. */
    public String unavailableReason() {
        if (bank == null) {
            return "it didn't start: " + unavailableReason + ". See the server log, then restart the app";
        }
        return bank.isRunning() ? null : "anvil stopped running. Restart the app";
    }

    /** A user-facing reason contract creation didn't happen. */
    public static final class CreationException extends RuntimeException {
        CreationException(String message) {
            super(message);
        }
    }

    /** What the chain says about a pool right now. */
    public record PoolState(boolean exists, BigDecimal totalDeposited, Instant depositsCloseAt) {
    }

    /** Reads the pool's state from the chain, or returns null if the chain isn't reachable. */
    public PoolState poolState(long poolId) {
        if (!available()) {
            return null;
        }
        try {
            BigInteger id = BigInteger.valueOf(poolId);
            BigInteger endTime = bank.poolEndTime(id);
            return new PoolState(endTime.signum() != 0, bank.totalDeposited(id), Instant.ofEpochSecond(endTime.longValueExact()));
        } catch (Exception e) {
            LOG.warn("Couldn't read pool {} from the chain", poolId, e);
            return null;
        }
    }

    /**
     * Creates the project's pool and deposits each member's stake. Only the project's creator can do this, and only
     * once the project is ready (see {@link Project#contractBlockers()}).
     *
     * @throws CreationException with a message to show the user if it can't be done or fails partway
     */
    public void create(int projectId, int requestingUserId) {
        if (!available()) {
            throw new CreationException("The blockchain isn't running, so the contract can't be created: "
                    + unavailableReason() + ".");
        }
        creationLock.lock();
        try {
            Plan plan = reserve(projectId, requestingUserId);
            Map<Integer, String> wallets = createOnChain(plan);
            record(projectId, wallets);
        } finally {
            creationLock.unlock();
        }
    }

    /** A detached snapshot of what has to happen on-chain. */
    private record Plan(BigInteger poolId, BigDecimal stake, LocalDateTime deadline, List<Member> members) {
    }

    private record Member(int userId, String name) {
    }

    /** Step 1 (database): check the project can have a contract, and save its pool ID before touching the chain. */
    private Plan reserve(int projectId, int requestingUserId) {
        return Database.transaction(em -> {
            Project project = em.find(Project.class, projectId);
            if (project == null) {
                throw new CreationException("Project not found.");
            }
            if (project.createdBy().userID() != requestingUserId) {
                throw new CreationException("Only the project's creator can create the smart contract.");
            }
            if (project.contractStatus() == Project.ContractStatus.CREATED) {
                throw new CreationException("The smart contract has already been created.");
            }
            List<String> blockers = project.contractBlockers();
            if (!blockers.isEmpty()) {
                throw new CreationException("Not ready yet: " + String.join("; ", blockers) + ".");
            }
            project.beginContract(RANDOM.nextLong() & Long.MAX_VALUE);
            List<Member> members = project.users().stream().map(u -> new Member(u.userID(), u.username())).toList();
            return new Plan(BigInteger.valueOf(project.poolId()), project.stakeAmount(), project.deadline(), members);
        });
    }

    /** Step 2 (chain): create the pool if it isn't there yet, then deposit whatever each member still owes. */
    private Map<Integer, String> createOnChain(Plan plan) {
        String step = "creating the pool";
        try {
            if (bank.poolEndTime(plan.poolId()).signum() == 0) {
                bank.ownerContract(plan.poolId()).createPool(secondsUntil(plan.deadline(), bank.chainTime()));
            }
            Map<Integer, String> wallets = new LinkedHashMap<>();
            for (Member member : plan.members()) {
                step = "depositing " + member.name() + "'s stake";
                // A detached copy with no address, so the bank always (re)derives the wallet and makes sure it has gas.
                User wallet = bank.login(new User(member.userId(), member.name(), null, null));
                wallets.put(member.userId(), wallet.walletAddress());
                BigDecimal owed = plan.stake().subtract(bank.depositOf(plan.poolId(), wallet.walletAddress()));
                if (owed.signum() > 0) {
                    bank.mintUSDC(wallet, owed); // demo chain: mint the stake as mock USDC, then deposit it
                    bank.contractFor(wallet, plan.poolId()).deposit(owed);
                }
            }
            step = "checking the pool";
            BigDecimal expected = plan.stake().multiply(BigDecimal.valueOf(plan.members().size()));
            BigDecimal actual = bank.totalDeposited(plan.poolId());
            if (actual.compareTo(expected) != 0) {
                throw new IllegalStateException("the pool holds " + actual.toPlainString()
                        + " USDC but should hold " + expected.toPlainString());
            }
            LOG.info("Created pool {} holding {} USDC from {} members", plan.poolId(), actual.toPlainString(), plan.members().size());
            return wallets;
        } catch (CreationException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Contract creation failed while {} (pool {})", step, plan.poolId(), e);
            String cause = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            throw new CreationException("Creating the contract failed while " + step + ": " + cause
                    + ". Nothing has been lost. Press the button again to finish.");
        }
    }

    /** Step 3 (database): save the members' wallet addresses and mark the contract as created. */
    private void record(int projectId, Map<Integer, String> wallets) {
        Database.transaction(em -> {
            wallets.forEach((userId, address) -> em.find(User.class, userId).setWalletAddress(address));
            em.find(Project.class, projectId).markContractCreated(bank.poolContractAddress());
            return null;
        });
    }

    /**
     * Seconds from the chain's latest block until the deadline (entered in the server's time zone), at least 1.
     * The contract adds this to the next block's timestamp, which is never earlier than the latest one, so deposits
     * close at the deadline or a second or so after it, never before.
     */
    private static long secondsUntil(LocalDateTime deadline, long chainNow) {
        long end = deadline.atZone(ZoneId.systemDefault()).toEpochSecond();
        return Math.max(1, end - chainNow);
    }

    @Override
    public void close() {
        if (bank != null) {
            bank.close();
        }
    }
}
