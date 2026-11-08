package com.stolengalaxy.web;

import com.stolengalaxy.Invite;
import com.stolengalaxy.MemberResult;
import com.stolengalaxy.Project;
import com.stolengalaxy.ProjectEvaluation;
import com.stolengalaxy.ProjectEvaluator;
import com.stolengalaxy.Task;
import com.stolengalaxy.User;
import com.stolengalaxy.smartcontract.ProjectContracts;
import com.stolengalaxy.smartcontract.Wallets;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * JSON shapes sent to the frontend. Entities are never serialised directly: their relationships point at
 * each other (Gson would recurse forever) and they hold fields like password hashes that must stay private.
 * Build these inside {@link com.stolengalaxy.db.Database#read} so lazy relationships can load.
 */
final class Dtos {

    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK);

    private Dtos() {
    }

    record MeDto(int userId, String name, String initials, String email, WalletDto wallet,
                 List<ProjectSummaryDto> projects, List<InviteDto> invites) {
        static MeDto of(User user, List<Invite> pendingInvites, WalletDto wallet) {
            return new MeDto(user.userID(), user.username(), Dtos.initials(user.username()), user.email(), wallet,
                    user.projects().stream().map(ProjectSummaryDto::of).toList(),
                    pendingInvites.stream().map(InviteDto::of).toList());
        }
    }

    /**
     * The user's own wallet.
     *
     * @param available false if it couldn't be read; {@code error} says why, and address/balance are null
     * @param committed stakes recorded in projects whose contracts haven't been created yet (still in the wallet)
     * @param maxTopUp  most that can be added at once with the temporary "Add funds" button
     */
    record WalletDto(boolean available, String error, String address, BigDecimal balance, BigDecimal committed,
                     BigDecimal maxTopUp) {
        static WalletDto of(Wallets.Wallet wallet, String error, BigDecimal committed) {
            return wallet == null
                    ? new WalletDto(false, error, null, null, committed, Wallets.MAX_TOP_UP)
                    : new WalletDto(true, null, wallet.address(), wallet.balance(), committed, Wallets.MAX_TOP_UP);
        }
    }

    record ProjectSummaryDto(int id, String name) {
        static ProjectSummaryDto of(Project project) {
            return new ProjectSummaryDto(project.projectID(), project.name());
        }
    }

    record InviteDto(int id, String projectName, String from, String deadline, BigDecimal stake,
                     String task, int weightPercentage) {
        static InviteDto of(Invite invite) {
            Project project = invite.project();
            return new InviteDto(invite.inviteID(), project.name(), invite.invitedBy().username(),
                    DEADLINE_FORMAT.format(project.deadline()), project.stakeAmount(),
                    invite.objective(), invite.weightPercentage());
        }
    }

    record ProjectDto(int id, String name, String description, String repoUrl,
                      String deadline, boolean deadlinePassed, long deadlineEpochMillis,
                      BigDecimal stakeAmount, BigDecimal poolTotal, BigDecimal poolTarget,
                      boolean youStaked, boolean youCreated, String createdBy,
                      int assignedWeight, int reservedWeight, List<String> contractBlockers,
                      boolean locked, ContractDto contract, EvaluationDto evaluation,
                      List<MemberDto> members, List<TaskDto> tasks, List<PendingInviteDto> pendingInvites) {
        static ProjectDto of(Project project, User viewer, ContractDto contract, EvaluationDto evaluation) {
            BigDecimal stake = project.stakeAmount();
            return new ProjectDto(project.projectID(), project.name(), project.projectDescription(), project.repoURL(),
                    DEADLINE_FORMAT.format(project.deadline()), project.deadline().isBefore(LocalDateTime.now()),
                    // The deadline is entered in the server's time zone (as the contract uses it), so the browser counts down to the same instant
                    project.deadline().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    stake,
                    stake.multiply(BigDecimal.valueOf(project.stakedUsers().size())),
                    stake.multiply(BigDecimal.valueOf(project.users().size())),
                    project.hasStaked(viewer),
                    project.createdBy() == viewer, project.createdBy().username(),
                    project.assignedWeight(), project.reservedWeight(), project.contractBlockers(),
                    project.isLocked(), contract, evaluation,
                    project.users().stream().map(user -> MemberDto.of(project, user)).toList(),
                    project.tasks().stream().map(TaskDto::of).toList(),
                    project.invites().stream()
                            .filter(invite -> invite.status() == Invite.Status.PENDING)
                            .map(invite -> PendingInviteDto.of(invite, viewer))
                            .toList());
        }
    }

    /**
     * The project's smart contract.
     *
     * @param status         NONE, CREATING (started but unfinished; retrying finishes it) or CREATED
     * @param chainAvailable whether the local blockchain is running; {@code chainError} says why not
     * @param onChain        what the chain says about the pool right now, or null if it couldn't be read / doesn't apply
     */
    record ContractDto(String status, String poolId, String contractAddress, String createdAt,
                       boolean chainAvailable, String chainError, OnChainDto onChain) {
        static ContractDto of(Project project, ProjectContracts contracts) {
            OnChainDto onChain = null;
            if (project.poolId() != null && contracts.available()) {
                ProjectContracts.PoolState state = contracts.poolState(project.poolId());
                if (state != null) {
                    onChain = new OnChainDto(state.exists(), state.totalDeposited(),
                            state.exists() ? DEADLINE_FORMAT.format(LocalDateTime.ofInstant(state.depositsCloseAt(), ZoneId.systemDefault())) : null);
                }
            }
            return new ContractDto(project.contractStatus().name(),
                    project.poolId() == null ? null : project.poolId().toString(),
                    project.contractAddress(),
                    project.contractCreatedAt() == null ? null : DEADLINE_FORMAT.format(project.contractCreatedAt()),
                    contracts.available(), contracts.unavailableReason(), onChain);
        }
    }

    /**
     * The project's AI evaluation and payout.
     *
     * @param status      NONE (not requested yet), RUNNING, FAILED ({@code error} says why; it can be requested again) or PAID
     * @param requestable whether the button works once the deadline has passed; {@code blocker} says why not
     * @param scoresSaved whether Gemini's scores are in (a failed payout is retried with the same scores)
     * @param results     one per member once scored, with their score, feedback, evidence and payout
     */
    record EvaluationDto(String status, String requestedBy, String requestedAt, String finishedAt, String error,
                         boolean requestable, String blocker, boolean scoresSaved, List<ResultDto> results) {
        static EvaluationDto of(Project project, User viewer, boolean running, boolean chainAvailable) {
            ProjectEvaluation evaluation = project.evaluation();
            String status = evaluation == null ? "NONE" : evaluation.status().name();
            String error = evaluation == null ? null : evaluation.error();
            if (status.equals("RUNNING") && !running) {
                // Saved as running, but this server isn't working on it: it was cut off by a restart
                status = "FAILED";
                error = "The server restarted before the evaluation finished. Request it again to finish"
                        + (evaluation.hasScores() ? " (the scores are kept)." : ".");
            }
            String blocker = ProjectEvaluator.blockerOtherThanDeadline(project);
            if (blocker == null && !chainAvailable) {
                blocker = "The blockchain isn't running, so the pool can't be paid out.";
            }
            boolean requestable = blocker == null && !status.equals("RUNNING") && !status.equals("PAID");
            List<ResultDto> results = evaluation == null ? List.of()
                    : evaluation.results().stream().map(result -> ResultDto.of(result, project, viewer)).toList();
            return new EvaluationDto(status,
                    evaluation == null || evaluation.requestedBy() == null ? null : evaluation.requestedBy().username(),
                    evaluation == null || evaluation.requestedAt() == null ? null : DEADLINE_FORMAT.format(evaluation.requestedAt()),
                    evaluation == null || evaluation.finishedAt() == null ? null : DEADLINE_FORMAT.format(evaluation.finishedAt()),
                    error, requestable, blocker, evaluation != null && evaluation.hasScores(), results);
        }
    }

    /** One member's result: {@code change} is what they gained (positive) or lost (negative) against their stake. */
    record ResultDto(int userId, String name, String initials, boolean you, int score, String feedback, List<String> evidence,
                     int weightPercentage, BigDecimal staked, BigDecimal payout, BigDecimal change) {
        static ResultDto of(MemberResult result, Project project, User viewer) {
            User user = result.user();
            BigDecimal stake = project.stakeAmount();
            return new ResultDto(user.userID(), user.username(), Dtos.initials(user.username()), user == viewer,
                    result.score(), result.feedback(), result.evidence(), result.weightPercentage(),
                    stake, result.payout(), result.payout().subtract(stake));
        }
    }

    /** @param exists false if the pool isn't on the current chain (e.g. the chain's state was deleted) */
    record OnChainDto(boolean exists, BigDecimal totalDeposited, String depositsClose) {
    }

    /** An invitation sent from this project that hasn't been answered yet. */
    record PendingInviteDto(int id, String email, String task, int weightPercentage, String invitedBy, boolean canCancel) {
        static PendingInviteDto of(Invite invite, User viewer) {
            return new PendingInviteDto(invite.inviteID(), invite.email(), invite.objective(), invite.weightPercentage(),
                    invite.invitedBy().username(), invite.canBeCancelledBy(viewer));
        }
    }

    record MemberDto(int userId, String name, String initials, String githubUsername, String walletAddress,
                     boolean staked, boolean hasTask) {
        static MemberDto of(Project project, User user) {
            return new MemberDto(user.userID(), user.username(), Dtos.initials(user.username()), user.githubUsername(),
                    user.walletAddress(), project.hasStaked(user), project.taskFor(user).isPresent());
        }
    }

    record TaskDto(int id, String title, int weightPercentage, int assigneeId, String assigneeName, String assigneeInitials) {
        static TaskDto of(Task task) {
            User user = task.user();
            return new TaskDto(task.taskID(), task.objective(), task.weightPercentage(),
                    user.userID(), user.username(), initials(user.username()));
        }
    }

    /** "Alex Morgan" → "AM", "alice" → "AL". */
    static String initials(String name) {
        String[] words = name.trim().split("\\s+");
        String initials = words.length >= 2
                ? "" + words[0].charAt(0) + words[1].charAt(0)
                : words[0].substring(0, Math.min(2, words[0].length()));
        return initials.toUpperCase(Locale.ROOT);
    }
}
