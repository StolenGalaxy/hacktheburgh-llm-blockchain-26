package com.stolengalaxy.smartcontract;

import com.stolengalaxy.Project;
import com.stolengalaxy.User;
import com.stolengalaxy.db.Database;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public final class ProjectContracts implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectContracts.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Bank bank; // null when the chain couldn't start
    private final String unavailableReason;
    private final Wallets wallets;
    private final ReentrantLock creationLock = new ReentrantLock();

    private ProjectContracts(Bank bank, String unavailableReason) {
        this.bank = bank;
        this.unavailableReason = unavailableReason;
        this.wallets = new Wallets(bank);
    }

    public Wallets wallets() {
        return wallets;
    }

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

    public String unavailableReason() {
        if (bank == null) {
            return "it didn't start: " + unavailableReason + ". See the server log, then restart the app";
        }
        return bank.isRunning() ? null : "anvil stopped running. Restart the app";
    }

    public static final class CreationException extends RuntimeException {
        CreationException(String message) {
            super(message);
        }
    }

    public record PoolState(boolean exists, BigDecimal totalDeposited, Instant depositsCloseAt) {
    }

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

    public void create(int projectId, int requestingUserId) {
        if (!available()) {
            throw new CreationException("The blockchain isn't running, so the contract can't be created: "
                    + unavailableReason() + ".");
        }
        creationLock.lock();
        try {
            // Before anything is saved or sent: can every member cover their stake? (Under the lock, since only
            // contract creation takes money out of wallets.)
            checkFunds(Database.read(em -> plan(validated(em, projectId, requestingUserId))));
            Plan plan = reserve(projectId, requestingUserId);
            Map<Integer, String> wallets = createOnChain(plan);
            record(projectId, wallets);
        } finally {
            creationLock.unlock();
        }
    }

    private record Plan(BigInteger poolId, BigDecimal stake, LocalDateTime deadline, List<Member> members) {
    }

    private record Member(int userId, String name) {
    }

    private static Project validated(EntityManager em, int projectId, int requestingUserId) {
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
        return project;
    }

    private static Plan plan(Project project) {
        List<Member> members = project.users().stream().map(u -> new Member(u.userID(), u.username())).toList();
        BigInteger poolId = project.poolId() == null ? null : BigInteger.valueOf(project.poolId());
        return new Plan(poolId, project.stakeAmount(), project.deadline(), members);
    }

    private void checkFunds(Plan plan) {
        List<String> shortfalls = new ArrayList<>();
        try {
            for (Member member : plan.members()) {
                String address = bank.openWallet(member.userId());
                BigDecimal deposited = plan.poolId() == null ? BigDecimal.ZERO : bank.depositOf(plan.poolId(), address);
                BigDecimal owed = plan.stake().subtract(deposited);
                BigDecimal balance = bank.balanceOf(address);
                if (owed.signum() > 0 && balance.compareTo(owed) < 0) {
                    shortfalls.add(member.name() + " has " + Wallets.dollars(balance) + " but needs " + Wallets.dollars(owed));
                }
            }
        } catch (Exception e) {
            LOG.error("Couldn't check members' wallets (pool {})", plan.poolId(), e);
            throw new CreationException("Couldn't check members' wallets on the blockchain. Nothing has changed. Try again.");
        }
        if (!shortfalls.isEmpty()) {
            throw new CreationException("Not every member has enough USDC for their stake: " + String.join("; ", shortfalls)
                    + ". They can add funds from their wallet, then press the button again.");
        }
    }

    private Plan reserve(int projectId, int requestingUserId) {
        return Database.transaction(em -> {
            Project project = validated(em, projectId, requestingUserId);
            project.beginContract(RANDOM.nextLong() & Long.MAX_VALUE);
            return plan(project);
        });
    }

    private Map<Integer, String> createOnChain(Plan plan) {
        String step = "creating the pool";
        try {
            if (bank.poolEndTime(plan.poolId()).signum() == 0) {
                bank.ownerContract(plan.poolId()).createPool(secondsUntil(plan.deadline(), bank.chainTime()));
            }
            Map<Integer, String> wallets = new LinkedHashMap<>();
            for (Member member : plan.members()) {
                step = "depositing " + member.name() + "'s stake";
                String address = bank.openWallet(member.userId()); // makes sure it has gas
                wallets.put(member.userId(), address);
                BigDecimal owed = plan.stake().subtract(bank.depositOf(plan.poolId(), address));
                if (owed.signum() > 0) {
                    // From the member's own wallet; checkFunds made sure it holds enough
                    bank.contractFor(new User(member.userId(), member.name(), address, null), plan.poolId()).deposit(owed);
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

    private void record(int projectId, Map<Integer, String> wallets) {
        Database.transaction(em -> {
            wallets.forEach((userId, address) -> em.find(User.class, userId).setWalletAddress(address));
            em.find(Project.class, projectId).markContractCreated(bank.poolContractAddress());
            return null;
        });
    }

    public static final class PayoutException extends RuntimeException {
        PayoutException(String message) {
            super(message);
        }
    }

    public void payOut(long poolId, Map<Integer, BigDecimal> payouts) {
        if (!available()) {
            throw new PayoutException("The blockchain isn't running, so the pool can't be paid out: " + unavailableReason() + ".");
        }
        BigInteger id = BigInteger.valueOf(poolId);
        creationLock.lock();
        String step = "reading the pool";
        try {
            if (bank.poolEndTime(id).signum() == 0) {
                throw new PayoutException("This project's pool isn't on the current blockchain (its state was probably deleted), so it can't be paid out.");
            }
            if (!bank.isSettled(id)) {
                BigDecimal held = bank.totalDeposited(id);
                BigDecimal total = payouts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (total.compareTo(held) != 0) {
                    throw new PayoutException("The payouts add up to " + Wallets.dollars(total) + ", but the pool holds "
                            + Wallets.dollars(held) + ". They must match exactly.");
                }
                step = "settling the pool";
                Map<String, BigDecimal> byWallet = new LinkedHashMap<>();
                for (Map.Entry<Integer, BigDecimal> payout : payouts.entrySet()) {
                    if (payout.getValue().signum() > 0) { // a zero share has nothing to claim
                        byWallet.put(bank.openWallet(payout.getKey()), payout.getValue());
                    }
                }
                bank.ownerContract(id).payoutExact(byWallet);
                LOG.info("Settled pool {}: {}", id, byWallet);
            }
            for (Integer userId : payouts.keySet()) {
                step = "sending user " + userId + "'s share to their wallet";
                String address = bank.openWallet(userId); // makes sure it has gas to withdraw
                if (bank.claimableOf(id, address).signum() > 0) {
                    bank.contractFor(new User(userId, "user " + userId, address, null), id).withdraw();
                }
            }
        } catch (PayoutException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Paying out pool {} failed while {}", id, step, e);
            String cause = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            throw new PayoutException("Paying out the pool failed while " + step + ": " + cause
                    + ". Nothing has been lost. Request evaluation again to finish (the scores are kept).");
        } finally {
            creationLock.unlock();
        }
    }

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
