package com.history.pipeline_worker.service;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.http.MediaType;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class JiraRawService {

    private final WebClient.Builder webClientBuilder;
    private final String defaultBaseUrl;

    public JiraRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.jira.base-url}") String defaultBaseUrl
    ) {
        this.webClientBuilder = webClientBuilder;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public Map<String, Object> fetch(RawFetchRequest request) {
        String baseUrl = resolveBaseUrl(request);
        String auth = resolveAuth(request.credentials());

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();

        Map<String, Object> searchResult = fetchSearch(client, auth, request.projectKey());

        String firstIssueKey = extractFirstIssueKey(searchResult);
        List<Object> comments = firstIssueKey != null
                ? fetchComments(client, auth, firstIssueKey)
                : Collections.emptyList();

        return Map.of(
                "search", searchResult,
                "sampleComments", comments
        );
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

    // credentials 형식: "email:apiToken" 또는 이미 "Basic xxx" / "Bearer xxx"
    private String resolveAuth(String credentials) {
        if (credentials.startsWith("Basic ") || credentials.startsWith("Bearer ")) {
            return credentials;
        }
        // "email:apiToken" → Basic Base64
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        return "Basic " + encoded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSearch(WebClient client, String auth, String projectKey) {
        Map<String, Object> body = Map.of(
                "jql", "project=" + projectKey + " ORDER BY created DESC",
                "maxResults", 50,
                "expand", "changelog",
                "fields", List.of("summary", "status", "assignee", "reporter", "issuetype",
                        "priority", "created", "updated", "description", "labels", "parent")
        );

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

    @SuppressWarnings("unchecked")
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
