package com.history.backend.slack.service;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.slack.SlackProperties;
import com.history.backend.slack.dto.SlackAuthTestResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import lombok.extern.slf4j.Slf4j;

// Slack API 클라이언트 (연동 토큰 검증용)
@Slf4j
@Component
public class SlackClient {

    private final SlackProperties properties;
    private final RestClient restClient;

    public SlackClient(
            SlackProperties properties,
            @Qualifier("slackRestClient")
            RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // Slack 토큰 검증 및 workspace 정보 조회
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

        // Slack은 인증 실패도 HTTP 200으로 응답하므로 ok 필드로 판별
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
