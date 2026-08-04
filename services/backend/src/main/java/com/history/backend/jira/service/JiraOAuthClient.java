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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Jira authorization code.");
            }
            throw new BadGatewayException("Jira OAuth code exchange request failed.", exception);
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
            if (isDefiniteAuthFailure(exception)) {
                // refresh token이 폐기됨(재동의 취소·90일 미사용) — 호출부(JiraTokenService)가 이 예외를 보고
                // pending 되돌리기를 판단한다. 폐기 판정은 400/401/403으로만 좁힌다: 429는 rate limit이지
                // 폐기가 아니고, 5xx는 Atlassian 측 일시 장애다. 여기서 오판하면 아직 유효한 연동이
                // pending으로 강등되고, 개인정보 보고 배치가 재조회 실패를 폐기로 오해하게 된다.
                throw new UnauthorizedException("Jira refresh token is invalid or revoked.");
            }
            throw new BadGatewayException("Jira OAuth token refresh request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira OAuth token refresh request failed.", exception);
        }

        return validateTokens(response);
    }

    /**
     * refresh token 폐기 (연동 해제 시). Atlassian은 refresh token을 폐기하면 그로부터 파생된
     * access token도 함께 무효화하므로 refresh token 하나만 넘긴다.
     *
     * <p>Slack revoke와 같은 이유로 실패해도 예외를 던지지 않는다 — 이미 폐기됐거나(90일 미사용,
     * 사용자가 직접 취소) Atlassian 장애일 때 연동 해제 자체가 막히면 안 된다.</p>
     */
    public void revoke(String refreshToken) {
        Map<String, String> body = Map.of(
                "token", refreshToken,
                "token_type_hint", "refresh_token",
                "client_id", properties.clientId(),
                "client_secret", properties.clientSecret()
        );

        try {
            restClient
                    .post()
                    .uri(properties.revokeUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("Jira token revoke request failed. error={}", exception.getMessage());
        }
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

    // 400·401·403만 확정된 인증 실패(폐기)로 판정한다. 429(rate limit)·404 등 나머지 4xx와
    // 5xx는 Atlassian 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
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
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Jira access token.");
            }
            throw new BadGatewayException("Jira accessible resources request failed.", exception);
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
