package com.stolengalaxy;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A project's AI evaluation and payout, requested by a member after the deadline. There's at most one per project:
 * if it fails, requesting again reuses it.
 *
 * <p>Once Gemini's scores are in, they're saved as {@link #results()} before anything happens on-chain. A retry after
 * a failed payout reuses them instead of asking Gemini again, so the scores and payouts can't change between attempts.</p>
 */
@Entity
public class ProjectEvaluation {

    public enum Status {
        /** Gemini is scoring the members, or the pool is being paid out. */
        RUNNING,
        /** Something went wrong; {@link #error()} says what. Any member can request it again. */
        FAILED,
        /** Scored, settled on-chain, and every member's share sent to their wallet. */
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

    /** Why the last attempt failed, shown to the members. */
    @Lob
    private String error;

    /** One per member, saved once Gemini has scored them. */
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

    /** Marks an attempt as started by {@code user}. */
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

    /** Ends the attempt: paid out if {@code error} is null, otherwise failed with that message. */
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
