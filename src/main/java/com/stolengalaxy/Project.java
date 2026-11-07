package com.stolengalaxy;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Project {

    @Id
    @GeneratedValue
    private int projectID;

    @Lob
    @Column(nullable = false)
    private String projectDescription;

    private String repoURL;

    /** Members of the project. This side owns the many-to-many join table. */
    @ManyToMany
    @JoinTable(name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private List<User> users = new ArrayList<>();

    /** One task per member; tasks are saved and deleted together with the project. */
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Task> tasks = new ArrayList<>();

    protected Project() {
        // for JPA
    }

    public Project(String projectDescription, String repoURL) {
        this.projectDescription = projectDescription;
        this.repoURL = repoURL;
    }

    /** Adds a member to the project, keeping both sides of the relationship in sync. */
    public void addUser(User user) {
        if (!users.contains(user)) {
            users.add(user);
            user.projects().add(this);
        }
    }

    /** Removes a member and their task. */
    public void removeUser(User user) {
        users.remove(user);
        user.projects().remove(this);
        tasks.removeIf(task -> {
            if (task.user() == user) {
                user.tasks().remove(task);
                return true;
            }
            return false;
        });
    }

    /** Assigns a task to a user, adding them as a member if they aren't one already. */
    public Task assignTask(User user, String objective, int weightPercentage) {
        addUser(user);
        Task task = new Task(this, user, objective, weightPercentage);
        tasks.add(task);
        user.tasks().add(task);
        return task;
    }

    public int projectID() { return projectID; }
    public String projectDescription() { return projectDescription; }
    public String repoURL() { return repoURL; }
    public List<User> users() { return users; }
    public List<Task> tasks() { return tasks; }

    public void setProjectDescription(String projectDescription) { this.projectDescription = projectDescription; }
    public void setRepoURL(String repoURL) { this.repoURL = repoURL; }

    @Override
    public String toString() {
        return "Project[projectID=" + projectID + ", repoURL=" + repoURL + "]";
    }
}
