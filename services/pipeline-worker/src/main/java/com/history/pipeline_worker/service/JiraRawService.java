package com.history.pipeline_worker.service;

import com.history.pipeline_worker.checkpoint.FileCheckpointManager;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.ratelimit.JiraRateLimiter;
import com.history.pipeline_worker.util.JiraDateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class JiraRawService {

    private static final Pattern JIRA_PROJECT_KEY =
            Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    private static final int PAGE_SIZE = 100;

    private final WebClient.Builder webClientBuilder;
    private final String defaultBaseUrl;
    private final JiraRateLimiter rateLimiter;
    private final FileCheckpointManager checkpointManager;

    public JiraRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.jira.base-url}") String defaultBaseUrl,
            JiraRateLimiter rateLimiter,
            FileCheckpointManager checkpointManager
    ) {
        this.webClientBuilder = webClientBuilder;
        this.defaultBaseUrl = defaultBaseUrl;
        this.rateLimiter = rateLimiter;
        this.checkpointManager = checkpointManager;
    }

    public Map<String, Object> fetch(RawFetchRequest request) {
        String baseUrl = resolveBaseUrl(request);
        String auth = resolveAuth(request.credentials());
        String projectKey = resolveProjectKey(request.projectKey());

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();

        Instant lastScannedAt = checkpointManager.getCached().jira.lastScannedAt;

        Map<String, Object> searchResult = fetchAllSearchPages(client, auth, projectKey, lastScannedAt);
        Map<String, Object> filteredResult = filterIssuesByUpdated(searchResult, lastScannedAt);

        String firstIssueKey = extractFirstIssueKey(filteredResult);
        List<Object> comments = Collections.emptyList();
        if (firstIssueKey != null) {
            comments = fetchComments(client, auth, firstIssueKey);
            rateLimiter.acquire();
        }

        log.info("Jira 수집 완료: issues={}", extractIssueCount(filteredResult));

        return Map.of(
                "search", filteredResult,
                "sampleComments", comments
        );
    }

    /** lastScannedAt 이후 갱신된 이슈만 남기도록 searchResult 내 issues 필터링 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> filterIssuesByUpdated(Map<String, Object> searchResult, Instant lastScannedAt) {
        if (lastScannedAt == null || searchResult == null) {
            return searchResult != null ? searchResult : Map.of();
        }

        List<Map<String, Object>> issues = (List<Map<String, Object>>) searchResult.get("issues");
        if (issues == null) return searchResult;

        List<Map<String, Object>> filtered = issues.stream()
                .filter(issue -> {
                    Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                    if (fields == null) return true;
                    String updated = (String) fields.get("updated");
                    if (updated == null) return true;
                    try {
                        return JiraDateUtils.parse(updated).isAfter(lastScannedAt);
                    } catch (Exception e) {
                        return true;
                    }
                })
                .toList();

        Map<String, Object> result = new HashMap<>(searchResult);
        result.put("issues", filtered);
        return result;
    }

    private int extractIssueCount(Map<String, Object> searchResult) {
        if (searchResult == null) return 0;
        List<?> issues = (List<?>) searchResult.get("issues");
        return issues != null ? issues.size() : 0;
    }

    private String resolveBaseUrl(RawFetchRequest request) {
        if (request.options() != null && request.options().containsKey("baseUrl")) {
            return request.options().get("baseUrl");
        }
        if (defaultBaseUrl != null && !defaultBaseUrl.isBlank()) {
            return defaultBaseUrl;
        }
        throw new IllegalArgumentException("Jira baseUrl must be provided in options.baseUrl");
    }

    private String resolveProjectKey(String projectKey) {
        if (projectKey == null || !JIRA_PROJECT_KEY.matcher(projectKey).matches()) {
            throw new IllegalArgumentException("Invalid Jira project key: " + projectKey);
        }
        return projectKey;
    }

    // credentials 형식: "email:apiToken" 또는 이미 "Basic xxx" / "Bearer xxx"
    private String resolveAuth(String credentials) {
        if (credentials.startsWith("Basic ") || credentials.startsWith("Bearer ")) {
            return credentials;
        }
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        return "Basic " + encoded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchAllSearchPages(WebClient client, String auth, String projectKey,
                                                    Instant lastScannedAt) {
        List<Object> allIssues = new ArrayList<>();
        String nextPageToken = null;
        Map<String, Object> lastResponse = Map.of();

        do {
            lastResponse = fetchSearchPage(client, auth, projectKey, lastScannedAt, nextPageToken);
            rateLimiter.acquire();
            if (lastResponse == null) {
                return Map.of("issues", allIssues, "maxResults", PAGE_SIZE);
            }

            List<Object> issues = (List<Object>) lastResponse.get("issues");
            if (issues != null) allIssues.addAll(issues);

            nextPageToken = (String) lastResponse.get("nextPageToken");
        } while (nextPageToken != null && !nextPageToken.isBlank());

        Map<String, Object> result = new HashMap<>(lastResponse);
        result.put("issues", allIssues);
        result.put("maxResults", PAGE_SIZE);
        return result;
    }

    private Map<String, Object> fetchSearchPage(WebClient client, String auth, String projectKey,
                                                Instant lastScannedAt, String nextPageToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("jql", buildJql(projectKey, lastScannedAt));
        body.put("maxResults", PAGE_SIZE);
        body.put("expand", "changelog");
        body.put("fields", List.of("summary", "status", "assignee", "reporter", "issuetype",
                "priority", "created", "updated", "description", "labels", "parent"));
        if (nextPageToken != null && !nextPageToken.isBlank()) {
            body.put("nextPageToken", nextPageToken);
        }

        return client.post()
                .uri("/rest/api/3/search/jql")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    private String buildJql(String projectKey, Instant lastScannedAt) {
        String jql = "project=" + projectKey;
        if (lastScannedAt != null) {
            jql += " AND updated >= \"" + JiraDateUtils.formatForJql(lastScannedAt) + "\"";
        }
        return jql + " ORDER BY updated DESC";
    }

    private String extractFirstIssueKey(Map<String, Object> searchResult) {
        if (searchResult == null) return null;
        List<Map<String, Object>> issues = (List<Map<String, Object>>) searchResult.get("issues");
        if (issues == null || issues.isEmpty()) return null;
        return (String) issues.get(0).get("key");
    }

    @SuppressWarnings("unchecked")
    private List<Object> fetchComments(WebClient client, String auth, String issueKey) {
        Map<String, Object> result = client.get()
                .uri("/rest/api/3/issue/{issueKey}/comment?maxResults=3", issueKey)
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (result == null) return Collections.emptyList();
        List<Object> comments = (List<Object>) result.get("comments");
        return comments != null ? comments : Collections.emptyList();
    }
}
