package com.stolengalaxy;

import java.math.BigDecimal;
import java.util.ArrayList;
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

        ArrayList<Integer> successfulUsers = new ArrayList<Integer>();
        double payoutTotal = 0;
        double stakeAmount = stake.doubleValue();
        for (Integer userId : scores.keySet()){
            int userScore = scores.get(userId);


            if (userScore <= 6 && userScore < averageScore){
                System.out.println("score too low");
                double lossFraction = (averageScore - userScore) / averageScore;   // score 2, avg 5.33 → 0.625 (lose 62.5%)
                double loss = stakeAmount * lossFraction;

                payouts.put(userId, stakeAmount - loss);
                payoutTotal += loss;
            } else if (userScore >=7) {
                System.out.println("score is good, paying out");
                successfulUsers.add(userId);
            }

            //double payFactor = userScore / averageScore;
            //double payout = payFactor * stake.doubleValue();
            //System.out.println(userScore + " score");
            //System.out.println(payFactor + " factor");
            //System.out.println(payout + " payout");
            //payouts.put(userId, payout);

        }

        for (Integer userId : successfulUsers){
            double payAmount = payoutTotal / successfulUsers.size();
            double payout = stakeAmount + payAmount;
            System.out.println(userId + " getting paid payout: " + payout);
            payouts.put(userId, payout);
        }
        return payouts;
    }

}
