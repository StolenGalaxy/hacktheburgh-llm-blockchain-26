package com.stolengalaxy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;

public class payoutAlgorithm {
    private static float getAverageScore(HashMap<Integer, Integer> scores){
        int totalScore = 0;
        for (int score : scores.values()) {
            totalScore += score;
        }
        return (float) totalScore / scores.size();
    }

    private static final HashMap<Integer, User> users = new HashMap<Integer, User>();



    private static final HashMap<Integer, Integer> scores = new HashMap<Integer, Integer>();
    private static final HashMap<Integer, Double> payouts = new HashMap<Integer, Double>();

    public static HashMap<Integer, Double>  calculatePayout(List<Evaluation> evals, Project project) {
        BigDecimal stake = project.stakeAmount();

        for (Evaluation eval : evals){
            scores.put(eval.userId(), eval.score());
            //System.out.println(eval.score() + " scored");
        }
        float averageScore = getAverageScore(scores);
        System.out.println(averageScore + " average");


        for (Integer userId : scores.keySet()){
            int userScore = scores.get(userId);
            double payFactor = userScore / averageScore;
            double payout = payFactor * stake.doubleValue();
            System.out.println(userScore + " score");
            System.out.println(payFactor + " factor");
            System.out.println(payout + " payout");
            payouts.put(userId, payout);
        }
        return payouts;
    }

}
