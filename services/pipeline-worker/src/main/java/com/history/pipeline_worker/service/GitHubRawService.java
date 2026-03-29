package com.history.pipeline_worker.service;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GitHubRawService {

    private final WebClient webClient;

    public GitHubRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.github.base-url}") String baseUrl
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public Map<String, Object> fetch(RawFetchRequest request) {
        String[] parts = request.projectKey().split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("projectKey must be in 'owner/repo' format");
        }
        String owner = parts[0];
        String repo = parts[1];
        String auth = request.credentials();

        List<Object> rawCommits = fetchList(auth, "/repos/{owner}/{repo}/commits?per_page=5", owner, repo);
        List<Object> commits = enrichCommits(auth, rawCommits, owner, repo);
        List<Object> pullRequests = fetchList(auth, "/repos/{owner}/{repo}/pulls?state=all&per_page=5", owner, repo);
        List<Object> issues = fetchList(auth, "/repos/{owner}/{repo}/issues?state=all&per_page=5", owner, repo);

        return Map.of(
                "commits", commits,
                "pullRequests", pullRequests,
                "issues", issues
        );
    }

    @SuppressWarnings("unchecked")
    private List<Object> enrichCommits(String auth, List<Object> commits, String owner, String repo) {
        List<Object> result = new ArrayList<>();
        for (Object raw : commits) {
            Map<String, Object> commit = new HashMap<>((Map<String, Object>) raw);
            String sha = (String) commit.get("sha");
            Map<String, Object> detail = fetchCommitDetail(auth, owner, repo, sha);
            if (detail != null) {
                commit.put("files", detail.get("files"));
            }
            result.add(commit);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCommitDetail(String auth, String owner, String repo, String sha) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                .header("Authorization", auth)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    @SuppressWarnings("unchecked")
    private List<Object> fetchList(String auth, String path, String owner, String repo) {
        return webClient.get()
                .uri(path, owner, repo)
                .header("Authorization", auth)
                .retrieve()
                .bodyToMono(List.class)
                .block();
    }
}
