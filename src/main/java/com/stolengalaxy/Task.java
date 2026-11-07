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

    /** What the user has to do, e.g. "Build the project dashboard". */
    @Lob
    @Column(nullable = false)
    private String objective;

    /** This task's share of the project's work, 1-100. A project's task weights must total 100 before its contract is created. */
    @Column(nullable = false)
    private int weightPercentage;

    protected Task() {
        // for JPA
    }

    Task(Project project, User user, String objective, int weightPercentage) {
        this.project = project;
        this.user = user;
        this.objective = objective;
        this.weightPercentage = weightPercentage;
    }

    public int taskID() { return taskID; }
    public Project project() { return project; }
    public User user() { return user; }
    public String objective() { return objective; }
    public int weightPercentage() { return weightPercentage; }

    public void setObjective(String objective) { this.objective = objective; }
    public void setWeightPercentage(int weightPercentage) { this.weightPercentage = weightPercentage; }

    @Override
    public String toString() {
        return "Task[taskID=" + taskID + ", user=" + user.username() + ", objective=" + objective
                + ", weight=" + weightPercentage + "%]";
    }
}
