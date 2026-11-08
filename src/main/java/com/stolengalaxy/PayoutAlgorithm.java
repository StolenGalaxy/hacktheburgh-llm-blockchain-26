package com.stolengalaxy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PayoutAlgorithm {
    private static float getAverageScore(HashMap<Integer, Integer> scores){
        int totalScore = 0;
        for (int score : scores.values()) {
            totalScore += score;
        }
        return (float) totalScore / scores.size();
    }

    // Builds a fresh userId -> weight map each call, so old projects' weights never leak in
    private static HashMap<Integer, Integer> setupWeightsMap(List<Task> tasksList){
        HashMap<Integer, Integer> weights = new HashMap<Integer, Integer>();
        for (Task task : tasksList){
            weights.put(task.user().userID(), task.weightPercentage());
        }
        return weights;
    }




    public static HashMap<Integer, Double>  calculatePayout(List<Evaluation> evals, Project project) {
        // Fresh maps every call. (They used to be static fields that were never cleared, so each evaluation
        // kept the previous projects' scores and payouts and mixed them into its own.)
        HashMap<Integer, Integer> scores = new HashMap<Integer, Integer>();
        HashMap<Integer, Double> payouts = new HashMap<Integer, Double>();
        BigDecimal stake = project.stakeAmount();
        HashMap<Integer, Integer> weights = setupWeightsMap(project.tasks());
        for (Evaluation eval : evals){
            scores.put(eval.userId(), eval.score());

            //System.out.println(eval.score() + " scored");
        }
        float averageScore = getAverageScore(scores);
        System.out.println(averageScore + " average");

        ArrayList<Integer> successfulUsers = new ArrayList<Integer>();
        double payoutTotal = 0;
        double stakeAmount = stake.doubleValue();
        double totalStake = stakeAmount * scores.size();

        if (averageScore == 0){
            for (Integer userId : scores.keySet()){
                System.out.println("nobody got score, returning");
                payouts.put(userId, stakeAmount);
            }
            return payouts;
        }

        for (Integer userId : scores.keySet()){
            int userScore = scores.get(userId);
            // Money at risk is the user's weight share of the pool: 80% weight risks 4x what 20% does
            double deposit = totalStake * weights.get(userId) / 100.0;

            if (userScore <= 6 && userScore < averageScore){
                System.out.println("score too low");
                double lossFraction = (averageScore - userScore) / averageScore;   // score 2, avg 5.33 → 0.625 (lose 62.5%)
                double loss = deposit * lossFraction;

                payouts.put(userId, deposit - loss);
                payoutTotal += loss;
            } else if (userScore >=7) {
                System.out.println("score is good, paying out");
                successfulUsers.add(userId);
            }
            else{
                successfulUsers.add(userId);
            }
        }

        // Winners share the pot by weight, out of the WINNERS' total weight (not 100), so the whole pot is paid out
        int successfulWeight = 0;
        for (Integer userId : successfulUsers){
            successfulWeight += weights.get(userId);
        }

        for (Integer userId : successfulUsers){
            int userWeight = weights.get(userId);
            double deposit = totalStake * userWeight / 100.0;
            double payAmount = payoutTotal * userWeight / successfulWeight;
            double payout = deposit + payAmount;
            System.out.println(userId + " getting paid payout: " + payout);
            payouts.put(userId, payout);
        }
        return payouts;
    }

}
