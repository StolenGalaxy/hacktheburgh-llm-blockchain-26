package com.stolengalaxy.web;

import com.stolengalaxy.Invite;
import com.stolengalaxy.Project;
import com.stolengalaxy.Task;
import com.stolengalaxy.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    record MeDto(int userId, String name, String initials, String email,
                 List<ProjectSummaryDto> projects, List<InviteDto> invites) {
        static MeDto of(User user, List<Invite> pendingInvites) {
            return new MeDto(user.userID(), user.username(), Dtos.initials(user.username()), user.email(),
                    user.projects().stream().map(ProjectSummaryDto::of).toList(),
                    pendingInvites.stream().map(InviteDto::of).toList());
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
                      String deadline, boolean deadlinePassed,
                      BigDecimal stakeAmount, BigDecimal poolTotal, BigDecimal poolTarget,
                      boolean youStaked, boolean youCreated, String createdBy,
                      int assignedWeight, int reservedWeight, List<String> contractBlockers,
                      List<MemberDto> members, List<TaskDto> tasks) {
        static ProjectDto of(Project project, User viewer) {
            BigDecimal stake = project.stakeAmount();
            return new ProjectDto(project.projectID(), project.name(), project.projectDescription(), project.repoURL(),
                    DEADLINE_FORMAT.format(project.deadline()), project.deadline().isBefore(LocalDateTime.now()),
                    stake,
                    stake.multiply(BigDecimal.valueOf(project.stakedUsers().size())),
                    stake.multiply(BigDecimal.valueOf(project.users().size())),
                    project.hasStaked(viewer),
                    project.createdBy() == viewer, project.createdBy().username(),
                    project.assignedWeight(), project.reservedWeight(), project.contractBlockers(),
                    project.users().stream().map(user -> MemberDto.of(project, user)).toList(),
                    project.tasks().stream().map(TaskDto::of).toList());
        }
    }

    record MemberDto(int userId, String name, String initials, String githubUsername, boolean staked, boolean hasTask) {
        static MemberDto of(Project project, User user) {
            return new MemberDto(user.userID(), user.username(), Dtos.initials(user.username()), user.githubUsername(),
                    project.hasStaked(user), project.taskFor(user).isPresent());
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
