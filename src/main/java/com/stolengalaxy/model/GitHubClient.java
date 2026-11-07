package com.stolengalaxy.model;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern COMMIT_SHA = Pattern.compile("/commit/([0-9a-f]{7,40})\",\"authoredDate\":\"");

    public static List<String> listCommitShas(String owner, String repo, String branch, String author)
            throws IOException, InterruptedException {
        String commitsUrl = "https://github.com/" + owner + "/" + repo + "/commits/" + branch
                + "?author=" + encode(author);

        List<String> commits = new ArrayList<>(findShas(get(commitsUrl)));
        if (commits.isEmpty()) {
            return commits;
        }
        String newestCommit = commits.get(0);

        while (true) {
            String pageUrl = commitsUrl + "&after=" + encode(newestCommit + " " + (commits.size() - 1));
            List<String> newCommits = findShas(get(pageUrl));
            newCommits.removeAll(commits);
            if (newCommits.isEmpty()) {
                return commits;
            }
            commits.addAll(newCommits);
        }
    }

    public static String fetchPatch(String owner, String repo, String sha) throws IOException, InterruptedException {
        return cleanPatch(get("https://github.com/" + owner + "/" + repo + "/commit/" + sha + ".patch"));
    }

    private static final int MAX_FILE_DIFF_CHARS = 20_000;

    private static String cleanPatch(String patch) {
        StringBuilder out = new StringBuilder();
        for (String section : patch.split("(?m)(?=^diff --git )")) {
            if (section.contains("\nGIT binary patch\n")) {
                String header = section.substring(0, section.indexOf('\n') + 1);
                out.append(header).append("(binary file, contents omitted)\n");
            } else if (section.length() > MAX_FILE_DIFF_CHARS) {
                out.append(section, 0, MAX_FILE_DIFF_CHARS).append("\n(diff truncated)\n");
            } else {
                out.append(section);
            }
        }
        return out.toString();
    }

    private static List<String> findShas(String page) {
        List<String> shas = new ArrayList<>();
        Matcher matcher = COMMIT_SHA.matcher(page);
        while (matcher.find()) {
            shas.add(matcher.group(1));
        }
        return shas;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "htb-commit-evaluator")
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub returned " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
