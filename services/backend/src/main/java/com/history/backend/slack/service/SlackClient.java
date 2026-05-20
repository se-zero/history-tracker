package com.history.backend.slack.service;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.slack.SlackProperties;
import com.history.backend.slack.dto.SlackAuthTestResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SlackClient {

    private final SlackProperties properties;
    private final RestClient restClient;

    @Autowired
    public SlackClient(
            SlackProperties properties,
            @Qualifier("slackRestClient")
            RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public SlackWorkspace verifyToken(String token) {
        SlackAuthTestResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.authTestUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(SlackAuthTestResponse.class);
        } catch (RestClientResponseException exception) {
            throw new UnauthorizedException("Invalid Slack token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Slack auth test request failed.", exception);
        }

        if (response == null || !Boolean.TRUE.equals(response.ok())) {
            log.warn("Slack auth test failed. error={}", response == null ? "empty_response" : response.error());
            throw new UnauthorizedException("Invalid Slack token.");
        }
        if (response.teamId() == null || response.teamId().isBlank()
                || response.team() == null || response.team().isBlank()) {
            throw new BadGatewayException("Slack auth test response is missing workspace information.");
        }
        return new SlackWorkspace(response.teamId(), response.team());
    }

    public record SlackWorkspace(String id, String name) {
    }
}
