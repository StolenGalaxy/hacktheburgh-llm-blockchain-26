package com.stolengalaxy;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class ProjectEvaluation {

    public enum Status {
        RUNNING,
        FAILED,
        PAID
    }

    @Id
    @GeneratedValue
    private int evaluationID;

    @OneToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @ManyToOne
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    private LocalDateTime requestedAt;

    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.RUNNING;

    @Lob
    private String error;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("resultID")
    private List<MemberResult> results = new ArrayList<>();

    protected ProjectEvaluation() {
        // for JPA
    }

    public ProjectEvaluation(Project project) {
        this.project = project;
        project.setEvaluation(this);
    }

    public void start(User user) {
        if (status == Status.PAID) {
            throw new IllegalStateException("This project has already been paid out");
        }
        status = Status.RUNNING;
        requestedBy = user;
        requestedAt = LocalDateTime.now();
        finishedAt = null;
        error = null;
    }

    public void finish(String error) {
        this.status = error == null ? Status.PAID : Status.FAILED;
        this.error = error;
        this.finishedAt = LocalDateTime.now();
    }

    public void addResult(MemberResult result) {
        results.add(result);
    }

    public boolean hasScores() { return !results.isEmpty(); }

    public int evaluationID() { return evaluationID; }
    public Project project() { return project; }
    public User requestedBy() { return requestedBy; }
    public LocalDateTime requestedAt() { return requestedAt; }
    public LocalDateTime finishedAt() { return finishedAt; }
    public Status status() { return status; }
    public String error() { return error; }
    public List<MemberResult> results() { return results; }
}
