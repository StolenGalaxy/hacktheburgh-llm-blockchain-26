package com.stolengalaxy;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.List;

@Entity
public class MemberResult {

    private static final Gson GSON = new Gson();

    @Id
    @GeneratedValue
    private int resultID;

    @ManyToOne(optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private ProjectEvaluation evaluation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int score;

    @Lob
    private String feedback;

    @Lob
    private String evidence;

    @Column(nullable = false)
    private int weightPercentage;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal payout;

    protected MemberResult() {
        // for JPA
    }

    public MemberResult(ProjectEvaluation evaluation, User user, int score, String feedback, List<String> evidence,
                        int weightPercentage, BigDecimal payout) {
        this.evaluation = evaluation;
        this.user = user;
        this.score = score;
        this.feedback = feedback;
        this.evidence = GSON.toJson(evidence == null ? List.of() : evidence);
        this.weightPercentage = weightPercentage;
        this.payout = payout;
    }

    public int resultID() { return resultID; }
    public ProjectEvaluation evaluation() { return evaluation; }
    public User user() { return user; }
    public int score() { return score; }
    public String feedback() { return feedback; }
    public int weightPercentage() { return weightPercentage; }
    public BigDecimal payout() { return payout; }

    public List<String> evidence() {
        List<String> list = evidence == null ? null : GSON.fromJson(evidence, new TypeToken<List<String>>() {}.getType());
        return list == null ? List.of() : list;
    }
}
