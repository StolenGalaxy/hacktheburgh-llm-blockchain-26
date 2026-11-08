package com.stolengalaxy.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads commits through the GitHub REST API. (Scraping github.com's HTML pages broke: they
 * now return 504 to non-browser clients.) Set GITHUB_TOKEN to raise the rate limit from
 * 60 to 5000 requests an hour.
 */
public class GitHubClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String API = "https://api.github.com";
    private static final int PER_PAGE = 100;

    /**
     * The SHAs of each user's commits on {@code branch}, keyed by the usernames given (newest first; a user with no
     * commits gets an empty list).
     *
     * <p>Commits are matched to users by the GitHub account GitHub links them to, not with the API's
     * {@code ?author=} filter: that filter matches by email, so it misses commits made under an account's old
     * username (e.g. {@code 123+oldname@users.noreply.github.com}) or with another email the account owns. Commits
     * GitHub can't link to any account are matched by their author name or noreply email as a fallback.</p>
     */
    public static Map<String, List<String>> listCommitShasByUser(String owner, String repo, String branch,
                                                                 Collection<String> usernames)
            throws IOException, InterruptedException {
        Map<String, String> byLowercase = new HashMap<>();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String username : usernames) {
            if (username != null && !username.isBlank()) {
                byLowercase.put(username.trim().toLowerCase(Locale.ROOT), username);
                result.put(username, new ArrayList<>());
            }
        }
        for (int page = 1; ; page++) {
            String url = API + "/repos/" + owner + "/" + repo + "/commits"
                    + "?sha=" + encode(branch)
                    + "&per_page=" + PER_PAGE
                    + "&page=" + page;
            String body = get(url, "application/vnd.github+json", true);
            if (body == null) {
                return result; // repository has no commits yet
            }
            JsonArray array = JsonParser.parseString(body).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject commit = element.getAsJsonObject();
                String user = byLowercase.get(authorKey(commit));
                if (user != null) {
                    result.get(user).add(commit.get("sha").getAsString());
                }
            }
            if (array.size() < PER_PAGE) {
                return result;
            }
        }
    }

    /** Lowercase GitHub login of a commit's author: the linked account if there is one, else a best guess. */
    private static String authorKey(JsonObject commit) {
        JsonElement account = commit.get("author");
        if (account != null && account.isJsonObject() && account.getAsJsonObject().has("login")) {
            return account.getAsJsonObject().get("login").getAsString().toLowerCase(Locale.ROOT);
        }
        JsonObject author = commit.getAsJsonObject("commit").getAsJsonObject("author");
        String email = author.has("email") ? author.get("email").getAsString().toLowerCase(Locale.ROOT) : "";
        if (email.endsWith("@users.noreply.github.com")) {
            String local = email.substring(0, email.indexOf('@'));
            return local.substring(local.indexOf('+') + 1); // "123+name" or just "name"
        }
        return author.has("name") ? author.get("name").getAsString().trim().toLowerCase(Locale.ROOT) : "";
    }

    public static String fetchPatch(String owner, String repo, String sha) throws IOException, InterruptedException {
        String url = API + "/repos/" + owner + "/" + repo + "/commits/" + sha;
        return cleanPatch(get(url, "application/vnd.github.patch", false));
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * @param emptyOk when true, a 409 (repository has no commits yet) returns null instead of throwing.
     */
    private static String get(String url, String accept, boolean emptyOk) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "htb-commit-evaluator")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28");
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token.trim());
        }

        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 200) {
            return response.body();
        }
        if (emptyOk && status == 409) {
            return null;
        }
        if ((status == 403 || status == 429)
                && "0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(null))) {
            throw new IOException("GitHub API rate limit reached (set GITHUB_TOKEN on the server to raise it)");
        }
        throw new IOException("GitHub returned " + status + " for " + url);
    }
}
