package com.stolengalaxy;

import jakarta.persistence.*;

/**
 * An invitation for someone (by email, so they may not have an account yet) to join a project with a task.
 * Create these with {@link Project#invite}.
 */
@Entity
public class Invite {

    public enum Status { PENDING, ACCEPTED, DECLINED }

    @Id
    @GeneratedValue
    private int inviteID;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(optional = false)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    /** Invitee's email, stored lower-case. */
    @Column(nullable = false)
    private String email;

    /** The task the invitee gets when they accept. */
    @Lob
    @Column(nullable = false)
    private String objective;

    /** Weight of the invited task, reserved out of the project's 100% while the invite is pending. */
    @Column(nullable = false)
    private int weightPercentage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    protected Invite() {
        // for JPA
    }

    Invite(Project project, User invitedBy, String email, String objective, int weightPercentage) {
        this.project = project;
        this.invitedBy = invitedBy;
        this.email = email;
        this.objective = objective;
        this.weightPercentage = weightPercentage;
    }

    /** Adds the user to the project with the invited task. */
    public void accept(User user) {
        project.assignTask(user, objective, weightPercentage);
        status = Status.ACCEPTED;
    }

    public void decline() {
        status = Status.DECLINED;
    }

    public int inviteID() { return inviteID; }
    public Project project() { return project; }
    public User invitedBy() { return invitedBy; }
    public String email() { return email; }
    public String objective() { return objective; }
    public int weightPercentage() { return weightPercentage; }
    public Status status() { return status; }
}
