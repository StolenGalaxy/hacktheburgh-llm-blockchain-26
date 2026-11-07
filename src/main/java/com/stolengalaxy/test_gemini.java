package com.stolengalaxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stolengalaxy.model.Gemini;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class test_gemini {
    public static void main(String[] args) throws IOException, InterruptedException {
        User user1 = new User(1, "Evan Thomas", "joxdfgxdgsghhn@.com", "evanthomas2");

        List<Task> tasks = new ArrayList<>();
        Project project1 = new Project("COMMITD", "a +pp thing", "https://github.com/StolenGalaxy/hacktheburgh-llm-blockchain-26", null, null, user1);

        Task task1 = new Task(project1, user1, "DO ANYTHING EXCEPT CURE CANCER", 100);
        tasks.add(task1);

        JsonObject response = Gemini.query("Testing", tasks, project1.repoURL());
        JsonArray jsonResponse = response.get("evaluations").getAsJsonArray();
        List<Evaluation> evaluations = EvaluationParser.parse(jsonResponse);
        System.out.println(evaluations);
    }
}
