package com.history.pipeline_worker.source.jira;

import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.util.JiraDateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class JiraRawService {

    public record JiraSearchPage(Map<String, Object> searchResult, String nextPageToken, boolean limitReached) {}
    public record JiraFetchContext(WebClient client, String auth, String projectKey, Instant lastScannedAt) {}

    private static final Pattern JIRA_PROJECT_KEY =
            Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    private static final int PAGE_SIZE = 100;

    private final WebClient.Builder webClientBuilder;
    private final String defaultBaseUrl;
    private final int maxPagesPerRun;
    private final JiraRateLimiter rateLimiter;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {};

    public JiraRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.jira.base-url}") String defaultBaseUrl,
            @Value("${app.jira.max-pages-per-run:50}") int maxPagesPerRun,
            JiraRateLimiter rateLimiter
    ) {
        this.webClientBuilder = webClientBuilder;
        this.defaultBaseUrl = defaultBaseUrl;
        this.maxPagesPerRun = maxPagesPerRun;
        this.rateLimiter = rateLimiter;
    }

    public Map<String, Object> fetchSample(RawFetchRequest request) {
        JiraFetchContext context = prepareFetchContext(request, null);

        JiraSearchPage page = fetchSearchPage(context, null, 1);
        Map<String, Object> filteredResult = page.searchResult();

        String firstIssueKey = extractFirstIssueKey(filteredResult);
        List<Object> comments = Collections.emptyList();
        if (firstIssueKey != null) {
            comments = fetchComments(context.client(), context.auth(), firstIssueKey);
            rateLimiter.acquire();
        }

        log.info("Jira 수집 완료: issues={}", extractIssueCount(filteredResult));

        return Map.of(
                "search", filteredResult,
                "sampleComments", comments
        );
    }

    public JiraFetchContext prepareFetchContext(RawFetchRequest request, Instant lastScannedAt) {
        String baseUrl = resolveBaseUrl(request);
        String auth = resolveAuth(request.credentials());
        String projectKey = resolveProjectKey(request.projectKey());
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        return new JiraFetchContext(client, auth, projectKey, lastScannedAt);
    }

    public JiraSearchPage fetchSearchPage(JiraFetchContext context, String nextPageToken, int pageNumber) {
        if (pageNumber > maxPagesPerRun) {
            log.warn("Jira max pages per run 도달: maxPages={}", maxPagesPerRun);
            return new JiraSearchPage(Map.of("issues", List.of(), "maxResults", PAGE_SIZE), null, true);
        }

        Map<String, Object> page = fetchSearchPage(
                context.client(),
                context.auth(),
                context.projectKey(),
                context.lastScannedAt(),
                nextPageToken
        );
        rateLimiter.acquire();
        if (page == null) {
            return new JiraSearchPage(Map.of("issues", List.of(), "maxResults", PAGE_SIZE), null, false);
        }

        Map<String, Object> filteredPage = filterIssuesByUpdated(page, context.lastScannedAt());
        return new JiraSearchPage(filteredPage, (String) page.get("nextPageToken"), false);
    }

    /** lastScannedAt 이후 갱신된 이슈만 남기도록 searchResult 내 issues 필터링 */
    private Map<String, Object> filterIssuesByUpdated(Map<String, Object> searchResult, Instant lastScannedAt) {
        if (lastScannedAt == null || searchResult == null) {
            return searchResult != null ? searchResult : Map.of();
        }

        List<Map<String, Object>> issues = extractIssueMaps(searchResult);
        if (issues.isEmpty()) return searchResult;

        List<Map<String, Object>> filtered = issues.stream()
                .filter(issue -> {
                    Map<String, Object> fields = toStringObjectMap(issue.get("fields"));
                    if (fields.isEmpty()) return true;
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

    // credentials 형식: "Bearer {access_token}". OAuth 전환으로 API 토큰(email:apiToken → Basic)
    // 경로는 제거됐다 — 사용자가 토큰을 직접 입력하는 연동 경로 자체가 없다.
    private String resolveAuth(String credentials) {
        if (!credentials.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Jira credentials must be a Bearer token.");
        }
        return credentials;
    }

    private Map<String, Object> fetchSearchPage(WebClient client, String auth, String projectKey,
                                                Instant lastScannedAt, String nextPageToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("jql", buildJql(projectKey, lastScannedAt));
        body.put("maxResults", PAGE_SIZE);
        body.put("fields", List.of("summary", "status", "assignee", "reporter", "issuetype",
                "priority", "created", "updated", "description", "labels", "parent", "resolutiondate"));
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
                .bodyToMono(MAP_RESPONSE)
                .block();
    }

    private String buildJql(String projectKey, Instant lastScannedAt) {
        String jql = "project=" + projectKey;
        if (lastScannedAt != null) {
            jql += " AND updated >= \"" + JiraDateUtils.formatForJql(lastScannedAt) + "\"";
        }
        return jql + " ORDER BY updated ASC";
    }

    private String extractFirstIssueKey(Map<String, Object> searchResult) {
        if (searchResult == null) return null;
        List<Map<String, Object>> issues = extractIssueMaps(searchResult);
        if (issues.isEmpty()) return null;
        return (String) issues.get(0).get("key");
    }

    private List<Object> fetchComments(WebClient client, String auth, String issueKey) {
        Map<String, Object> result = client.get()
                .uri("/rest/api/3/issue/{issueKey}/comment?maxResults=3", issueKey)
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block();

        if (result == null) return Collections.emptyList();
        Object comments = result.get("comments");
        return comments instanceof List<?> list ? List.copyOf(list) : Collections.emptyList();
    }

    private List<Map<String, Object>> extractIssueMaps(Map<String, Object> searchResult) {
        Object rawIssues = searchResult.get("issues");
        if (!(rawIssues instanceof List<?> issues)) return List.of();
        return issues.stream()
                .map(this::toStringObjectMap)
                .filter(issue -> !issue.isEmpty())
                .toList();
    }

    private Map<String, Object> toStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) return Map.of();
        Map<String, Object> result = new HashMap<>();
        rawMap.forEach((key, mapValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, mapValue);
            }
        });
        return result;
    }
}
