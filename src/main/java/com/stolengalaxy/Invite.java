package com.stolengalaxy;

import jakarta.persistence.*;

@Entity
public class Invite {

    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }

    @Id
    @GeneratedValue
    private int inviteID;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(optional = false)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    @Column(nullable = false)
    private String email;

    @Lob
    @Column(nullable = false)
    private String objective;

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

    public void accept(User user) {
        project.assignTask(user, objective, weightPercentage);
        status = Status.ACCEPTED;
    }

    public void decline() {
        status = Status.DECLINED;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    public boolean canBeCancelledBy(User user) {
        return invitedBy == user || project.createdBy() == user;
    }

    public int inviteID() { return inviteID; }
    public Project project() { return project; }
    public User invitedBy() { return invitedBy; }
    public String email() { return email; }
    public String objective() { return objective; }
    public int weightPercentage() { return weightPercentage; }
    public Status status() { return status; }
}
