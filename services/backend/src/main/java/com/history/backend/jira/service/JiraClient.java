package com.history.backend.jira.service;

import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.dto.JiraProjectSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Jira REST API 클라이언트 (cloudId 게이트웨이 경유, OAuth access token 기반)
@Slf4j
@Component
public class JiraClient {

    private final AtlassianProperties properties;
    private final RestClient restClient;

    public JiraClient(
            AtlassianProperties properties,
            @Qualifier("jiraRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // 사용자가 고를 수 있는 프로젝트 목록 조회
    public List<JiraProject> listProjects(String cloudId, String accessToken) {
        JiraProjectSearchResponse response;
        try {
            response = restClient
                    .get()
                    .uri(properties.apiGatewayUrl() + "/{cloudId}/rest/api/3/project/search?maxResults=100", cloudId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JiraProjectSearchResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("Jira project list request failed. status={} cloudId={}", exception.getStatusCode(), cloudId);
            throw new UnauthorizedException("Invalid Jira access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira project list request failed.", exception);
        }

        if (response == null || response.values() == null) {
            throw new BadGatewayException("Jira project list response is missing values.");
        }
        return response.values().stream()
                .map(value -> new JiraProject(value.key(), value.name()))
                .toList();
    }

    public record JiraProject(String key, String name) {
    }
}
