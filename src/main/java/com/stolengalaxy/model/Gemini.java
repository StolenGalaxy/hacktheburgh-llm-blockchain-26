package com.stolengalaxy.model;

import com.google.gson.*;
import com.stolengalaxy.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

public class Gemini {
    public static JsonObject query(String projectDescription, List<Task> tasks, String repoUrl) throws IOException, InterruptedException {
        String[] parts = repoUrl.replaceAll("(\\.git)?/?$", "").split("/");
        String owner = parts[3];
        String repo = parts[4];

        String systemPrompt = Files.readString(Path.of("src/main/resources/prompts/evaluator-system-prompt.txt"));

        JsonArray users = new JsonArray();
        for (Task task : tasks) {
            JsonObject user = new JsonObject();
            user.addProperty("user_id", task.user().userID());
            user.addProperty("github_username", task.user().githubUsername());
            user.addProperty("task", task.objective());
            users.add(user);
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String usersJson = gson.toJson(users);

        StringBuilder commitsText = new StringBuilder();
        for (Task task : tasks) {
            String username = task.user().githubUsername();
            for (String sha : GitHubClient.listCommitShas(owner, repo, "main", username)) {
                commitsText.append("=== commit by GitHub user: ").append(username).append(" ===\n");
                commitsText.append(GitHubClient.fetchPatch(owner, repo, sha)).append("\n");
            }
        }

        String input = "PROJECT_DESCRIPTION:\n" + projectDescription
                + "\n\nUSERS:\n" + usersJson
                + "\n\nCOMMITS:\n" + commitsText;
        System.out.println(input);

        return null;
    }
}
