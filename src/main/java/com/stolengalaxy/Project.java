package com.stolengalaxy;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Entity
public class Project {

    public enum ContractStatus {
        NONE,
        CREATING,
        CREATED
    }

    @Id
    @GeneratedValue
    private int projectID;

    @Column(nullable = false)
    private String name;

    @Lob
    @Column(nullable = false)
    private String projectDescription;

    private String repoURL;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal stakeAmount;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToMany
    @JoinTable(name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private List<User> users = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "project_stakes",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> stakedUsers = new LinkedHashSet<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("taskID")
    private List<Task> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Invite> invites = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus contractStatus = ContractStatus.NONE;

    private Long poolId;

    private String contractAddress;

    private LocalDateTime contractCreatedAt;

    @OneToOne(mappedBy = "project")
    private ProjectEvaluation evaluation;

    protected Project() {
        // for JPA
    }

    public Project(String name, String projectDescription, String repoURL,
                   LocalDateTime deadline, BigDecimal stakeAmount, User createdBy) {
        this.name = name;
        this.projectDescription = projectDescription;
        this.repoURL = repoURL;
        this.deadline = deadline;
        this.stakeAmount = stakeAmount;
        this.createdBy = createdBy;
        addUser(createdBy);
    }

    public void addUser(User user) {
        if (!users.contains(user)) {
            users.add(user);
            user.projects().add(this);
        }
    }

    public void removeUser(User user) {
        requireUnlocked();
        users.remove(user);
        user.projects().remove(this);
        stakedUsers.remove(user);
        taskFor(user).ifPresent(task -> {
            tasks.remove(task);
            user.tasks().remove(task);
        });
    }

    public void prepareForDelete() {
        requireUnlocked();
        for (User user : users) {
            user.projects().remove(this);
            user.tasks().removeAll(tasks);
        }
    }

    public Task assignTask(User user, String objective, int weightPercentage) {
        requireUnlocked();
        if (taskFor(user).isPresent()) {
            throw new IllegalStateException(user.username() + " already has a task in this project");
        }
        addUser(user);
        Task task = new Task(this, user, objective, weightPercentage);
        tasks.add(task);
        user.tasks().add(task);
        return task;
    }

    public Invite invite(User invitedBy, String email, String objective, int weightPercentage) {
        requireUnlocked();
        Invite invite = new Invite(this, invitedBy, email, objective, weightPercentage);
        invites.add(invite);
        return invite;
    }

    public void recordStake(User user) {
        requireUnlocked();
        if (!users.contains(user)) {
            throw new IllegalStateException(user.username() + " is not a member of this project");
        }
        stakedUsers.add(user);
    }

    public int assignedWeight() {
        return tasks.stream().mapToInt(Task::weightPercentage).sum();
    }

    public int reservedWeight() {
        return invites.stream()
                .filter(invite -> invite.status() == Invite.Status.PENDING)
                .mapToInt(Invite::weightPercentage).sum();
    }

    public List<String> contractBlockers() {
        List<String> blockers = new ArrayList<>();
        if (deadline.isBefore(LocalDateTime.now())) {
            blockers.add("The deadline has passed");
        }
        if (stakeAmount.signum() <= 0) {
            blockers.add("The stake per member must be more than 0");
        }
        for (User user : users) {
            if (taskFor(user).isEmpty()) {
                blockers.add(user.username() + " has no task");
            }
            if (!hasStaked(user)) {
                blockers.add(user.username() + " hasn't staked");
            }
        }
        if (assignedWeight() != 100) {
            blockers.add("Task weights total " + assignedWeight() + "%, not 100%");
        }
        if (reservedWeight() > 0) {
            blockers.add("Pending invitations need a response");
        }
        return blockers;
    }

    public void beginContract(long newPoolId) {
        if (contractStatus == ContractStatus.CREATED) {
            throw new IllegalStateException("The contract has already been created");
        }
        if (poolId == null) {
            poolId = newPoolId;
        }
        contractStatus = ContractStatus.CREATING;
    }

    public void markContractCreated(String contractAddress) {
        if (contractStatus != ContractStatus.CREATING) {
            throw new IllegalStateException("Contract creation wasn't started");
        }
        this.contractStatus = ContractStatus.CREATED;
        this.contractAddress = contractAddress;
        this.contractCreatedAt = LocalDateTime.now();
    }

    public boolean isLocked() {
        return contractStatus != ContractStatus.NONE;
    }

    private void requireUnlocked() {
        if (isLocked()) {
            throw new IllegalStateException("The project's smart contract has been created, so it can't be changed");
        }
    }

    public boolean isMember(User user) { return users.contains(user); }
    public boolean hasStaked(User user) { return stakedUsers.contains(user); }

    public Optional<Task> taskFor(User user) {
        return tasks.stream().filter(task -> task.user() == user).findFirst();
    }

    public int projectID() { return projectID; }
    public String name() { return name; }
    public String projectDescription() { return projectDescription; }
    public String repoURL() { return repoURL; }
    public LocalDateTime deadline() { return deadline; }
    public BigDecimal stakeAmount() { return stakeAmount; }
    public User createdBy() { return createdBy; }
    public List<User> users() { return users; }
    public Set<User> stakedUsers() { return stakedUsers; }
    public List<Task> tasks() { return tasks; }
    public List<Invite> invites() { return invites; }
    public ContractStatus contractStatus() { return contractStatus; }
    public Long poolId() { return poolId; }
    public String contractAddress() { return contractAddress; }
    public LocalDateTime contractCreatedAt() { return contractCreatedAt; }
    public ProjectEvaluation evaluation() { return evaluation; }

    void setEvaluation(ProjectEvaluation evaluation) { this.evaluation = evaluation; }

    public void setName(String name) { this.name = name; }
    public void setProjectDescription(String projectDescription) { this.projectDescription = projectDescription; }
    public void setRepoURL(String repoURL) { this.repoURL = repoURL; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }

    @Override
    public String toString() {
        return "Project[projectID=" + projectID + ", name=" + name + "]";
    }
}
