package com.stolengalaxy;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users") // "user" is a reserved word in many databases
public class User {

    @Id
    @GeneratedValue
    private int userID;

    @Column(nullable = false, unique = true)
    private String username;

    private String walletAddress;

    private String githubUsername;

    /** Projects this user is a member of. The owning side is {@link Project#users()}. */
    @ManyToMany(mappedBy = "users")
    private List<Project> projects = new ArrayList<>();

    /** Tasks assigned to this user, across all their projects. */
    @OneToMany(mappedBy = "user")
    private List<Task> tasks = new ArrayList<>();

    protected User() {
        // for JPA
    }

    public User(String username, String walletAddress, String githubUsername) {
        this.username = username;
        this.walletAddress = walletAddress;
        this.githubUsername = githubUsername;
    }

    /** Sets the ID explicitly; only for users that are never persisted (e.g. tests). */
    public User(int userID, String username, String walletAddress, String githubUsername) {
        this(username, walletAddress, githubUsername);
        this.userID = userID;
    }

    public int userID() { return userID; }
    public String username() { return username; }
    public String walletAddress() { return walletAddress; }
    public String githubUsername() { return githubUsername; }
    public List<Project> projects() { return projects; }
    public List<Task> tasks() { return tasks; }

    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }
    public void setGithubUsername(String githubUsername) { this.githubUsername = githubUsername; }

    @Override
    public String toString() {
        return "User[userID=" + userID + ", username=" + username + "]";
    }
}
