package com.history.backend.jira.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.dto.JiraProjectResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class JiraClient {

    private final RestClient restClient;

    @Autowired
    public JiraClient(@Qualifier("jiraRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

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
