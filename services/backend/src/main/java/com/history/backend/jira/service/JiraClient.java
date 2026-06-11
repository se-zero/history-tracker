package com.history.backend.jira.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.dto.JiraProjectResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Jira REST API 클라이언트 (연동 자격증명 검증용)
@Slf4j
@Component
public class JiraClient {

    private final RestClient restClient;

    public JiraClient(@Qualifier("jiraRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // Jira 프로젝트 존재·자격증명 검증
    public JiraProject verifyProject(
            String baseUrl,
            String projectKey,
            String email,
            String apiToken
    ) {
        JiraProjectResponse response;
        try {
            response = restClient
                    .get()
                    .uri(baseUrl + "/rest/api/3/project/{projectKey}", projectKey)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth(email, apiToken))
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JiraProjectResponse.class);
        } catch (RestClientResponseException exception) {
            // Jira의 오류 응답(404 포함)은 자격증명 또는 프로젝트 지정 오류로 간주해 401 처리
            log.warn(
                    "Jira project verification failed. status={} projectKey={}",
                    exception.getStatusCode(),
                    projectKey
            );
            throw new UnauthorizedException("Invalid Jira credentials or project.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira project verification request failed.", exception);
        }

        if (response == null || response.key() == null || response.key().isBlank()) {
            throw new BadGatewayException("Jira project verification response is missing project information.");
        }
        return new JiraProject(response.key(), response.name());
    }

    private String basicAuth(String email, String apiToken) {
        String credential = email + ":" + apiToken;
        String encoded = Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    public record JiraProject(String key, String name) {
    }
}
