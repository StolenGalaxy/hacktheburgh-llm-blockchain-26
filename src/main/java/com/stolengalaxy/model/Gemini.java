package com.stolengalaxy.model;

import com.google.gson.*;
import com.stolengalaxy.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;

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

        // One listing of the branch for everyone; each user's commits are included once even if they have several tasks
        Set<String> usernames = new LinkedHashSet<>();
        for (Task task : tasks) {
            usernames.add(task.user().githubUsername());
        }
        StringBuilder commitsText = new StringBuilder();
        Map<String, List<String>> shasByUser = GitHubClient.listCommitShasByUser(owner, repo, "main", usernames);
        for (Map.Entry<String, List<String>> entry : shasByUser.entrySet()) {
            for (String sha : entry.getValue()) {
                commitsText.append("=== commit by GitHub user: ").append(entry.getKey()).append(" ===\n");
                commitsText.append(GitHubClient.fetchPatch(owner, repo, sha)).append("\n");
            }
        }

        String input = "PROJECT_DESCRIPTION:\n" + projectDescription
                + "\n\nUSERS:\n" + usersJson
                + "\n\nCOMMITS:\n" + commitsText;

        String jsonFormat = Files.readString(Path.of("src/main/resources/prompts/evaluator-output-format.json"));

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .responseMimeType("application/json")
                .responseSchema(Schema.fromJson(jsonFormat))
                .build();

        Client client = Client.builder().apiKey(System.getenv("GEMINI_API_KEY")).build();
        GenerateContentResponse response = client.models.generateContent("gemini-3.8-flash", input, config);

        String text = response.text();
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
