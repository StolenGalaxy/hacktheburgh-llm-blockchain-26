package com.stolengalaxy;

import jakarta.persistence.*;

/** The piece of work one user is responsible for in one project. Create these with {@link Project#assignTask}. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}))
public class Task {

    @Id
    @GeneratedValue
    private int taskID;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Short statement of the task, e.g. "Build the project dashboard". */
    @Lob
    @Column(nullable = false)
    private String objective;

    /** Optional extra detail about the task. */
    @Lob
    private String details;

    private int weightPercentage;

    protected Task() {
        // for JPA
    }

    Task(Project project, User user, String objective, String details) {
        this.project = project;
        this.user = user;
        this.objective = objective;
        this.details = details;
    }

    public int taskID() { return taskID; }
    public Project project() { return project; }
    public User user() { return user; }
    public String objective() { return objective; }
    public String details() { return details; }
    public int weightPercentage() { return weightPercentage; }

    public void setObjective(String objective) { this.objective = objective; }
    public void setDetails(String details) { this.details = details; }
    public void setWeightPercentage(int weightPercentage) { this.weightPercentage = weightPercentage; }

    @Override
    public String toString() {
        return "Task[taskID=" + taskID + ", user=" + user.username() + ", objective=" + objective + "]";
    }
}
