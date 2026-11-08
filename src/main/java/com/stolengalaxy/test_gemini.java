package com.stolengalaxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stolengalaxy.model.Gemini;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class test_gemini {
    public static void main(String[] args) throws IOException, InterruptedException {
        User user1 = new User(1, "Evan Thomas", "joxdfgxdgsghhn@.com", "evanthomas2");
        User user2 = new User(2, "Evan Thomas", "joxdfgxdgsghhn@.com", "malik-ikrash");
        User user3 = new User(3, "Evan Thomas", "joxdfgxdgsghhn@.com", "stolengalaxy");

        List<Task> tasks = new ArrayList<>();
        Project project1 = new Project("COMMITD", "a +pp thing", "https://github.com/StolenGalaxy/hacktheburgh-llm-blockchain-26", null, BigDecimal.valueOf(500), user1);
        //Project project2 = new Project("COMMITD", "doing different stuff", "https://github.com/StolenGalaxy/hacktheburgh-llm-blockchain-26", null, null, user2);
        project1.addUser(user2);
        project1.addUser(user3);

        Task task1 = new Task(project1, user1, "DO ANYTHING EXCEPT CURE CANCER", 50);
        Task task2 = new Task(project1, user2, "Tasked to do a fair amount of low quality html, but not horrible", 50);
        Task task3 = new Task(project1, user3, "DO ANYTHING EXCEPT CURE CANCER", 50);

        tasks.add(task1);
        tasks.add(task2);
        tasks.add(task3);

        JsonObject response = Gemini.query("Testing", tasks, project1.repoURL());
        JsonArray jsonResponse = response.get("evaluations").getAsJsonArray();
        List<Evaluation> evaluations = EvaluationParser.parse(jsonResponse);
        for (Evaluation eval : evaluations){
            System.out.println(eval.score());
        }
        System.out.println(evaluations);
        //ArrayList<User> users = evaluations.get("")
        HashMap<Integer, Double> payouts = PayoutAlgorithm.calculatePayout(evaluations, project1);

        System.out.println(payouts);
    }
}
