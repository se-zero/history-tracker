package com.history.backend.jira.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.dto.JiraAccessibleResource;
import com.history.backend.jira.dto.JiraTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Atlassian OAuth API 클라이언트 (authorization code 교환, 접근 가능 사이트 조회).
// GitHubOAuthClient와 달리 토큰 엔드포인트가 form이 아니라 JSON body를 받는다.
@Slf4j
@Component
public class JiraOAuthClient {

    private final AtlassianProperties properties;
    private final RestClient restClient;

    public JiraOAuthClient(
            AtlassianProperties properties,
            @Qualifier("jiraRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 access/refresh token으로 교환
    public JiraTokens exchangeCode(String code) {
        Map<String, String> body = Map.of(
                "grant_type", "authorization_code",
                "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(),
                "code", code,
                "redirect_uri", properties.redirectUri()
        );

        JiraTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JiraTokenResponse.class);
        } catch (RestClientResponseException exception) {
            throw new UnauthorizedException("Invalid Jira authorization code.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira OAuth code exchange request failed.", exception);
        }

        return validateTokens(response);
    }

    // refresh token으로 access token 갱신. Atlassian은 갱신할 때마다 새 refresh token을 함께 내려주고
    // 직전 것을 즉시 무효화한다 — 응답의 refreshToken()을 반드시 덮어써 저장해야 다음 갱신이 성공한다.
    public JiraTokens refresh(String refreshToken) {
        Map<String, String> body = Map.of(
                "grant_type", "refresh_token",
                "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(),
                "refresh_token", refreshToken
        );

        JiraTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JiraTokenResponse.class);
        } catch (RestClientResponseException exception) {
            // refresh token이 폐기됨(재동의 취소·90일 미사용) — 호출부(JiraTokenService)가 이 예외를 보고
            // pending 되돌리기를 판단한다.
            throw new UnauthorizedException("Jira refresh token is invalid or revoked.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira OAuth token refresh request failed.", exception);
        }

        return validateTokens(response);
    }

    private JiraTokens validateTokens(JiraTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("Jira OAuth response is missing access token.");
        }
        // offline_access 스코프가 빠지면 refresh_token 없이 응답한다 — 여기서 막지 않으면 null로
        // 조용히 저장되고 토큰 갱신 시점에서야 "갱신 불가"로 드러난다.
        if (response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BadGatewayException("Jira OAuth response is missing refresh token.");
        }
        // expiresIn이 박싱 Long이라 null이 그대로 넘어가면 호출부의 Instant.plusSeconds(...)에서
        // 자동 언박싱 NPE가 난다.
        if (response.expiresIn() == null) {
            throw new BadGatewayException("Jira OAuth response is missing expires_in.");
        }
        return new JiraTokens(response.accessToken(), response.refreshToken(), response.expiresIn());
    }

    // 발급된 토큰으로 접근 가능한 Atlassian 사이트(cloudId 단위) 목록 조회
    public List<JiraSite> listAccessibleResources(String accessToken) {
        JiraAccessibleResource[] resources;
        try {
            resources = restClient
                    .get()
                    .uri(properties.accessibleResourcesUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JiraAccessibleResource[].class);
        } catch (RestClientResponseException exception) {
            throw new UnauthorizedException("Invalid Jira access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira accessible resources request failed.", exception);
        }

        if (resources == null) {
            return List.of();
        }
        return Arrays.stream(resources)
                .map(resource -> new JiraSite(resource.id(), resource.name(), resource.url()))
                .toList();
    }

    public record JiraTokens(String accessToken, String refreshToken, Long expiresIn) {
    }

    public record JiraSite(String cloudId, String name, String url) {
    }
}
