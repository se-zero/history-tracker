package com.history.backend.slack.service;

import java.util.Set;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.slack.SlackProperties;
import com.history.backend.slack.dto.SlackOAuthAccessResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import lombok.extern.slf4j.Slf4j;

// Slack OAuth API 클라이언트 (authorization code → user access token 교환)
@Slf4j
@Component
public class SlackClient {

    // 이미 무효화된 토큰을 지우려는 것뿐이라 실패가 아니라 성공으로 재해석하는 에러들
    private static final Set<String> ALREADY_REVOKED_ERRORS = Set.of(
            "invalid_auth", "token_revoked", "token_expired");

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

    // authorization code를 user 토큰(xoxp-)과 workspace 정보로 교환
    public SlackWorkspace exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());

        SlackOAuthAccessResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.oauthAccessUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SlackOAuthAccessResponse.class);
        } catch (RestClientResponseException exception) {
            throw new UnauthorizedException("Invalid Slack authorization code.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Slack OAuth code exchange request failed.", exception);
        }

        // Slack은 인증 실패도 HTTP 200으로 응답하므로 ok 필드로 판별
        if (response == null || !Boolean.TRUE.equals(response.ok())) {
            log.warn("Slack OAuth code exchange failed. error={}", response == null ? "empty_response" : response.error());
            throw new UnauthorizedException("Invalid Slack authorization code.");
        }

        String accessToken = response.authedUser() == null ? null : response.authedUser().accessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new BadGatewayException("Slack OAuth response is missing user access token.");
        }
        SlackOAuthAccessResponse.Team team = response.team();
        if (team == null || team.id() == null || team.id().isBlank() || team.name() == null || team.name().isBlank()) {
            throw new BadGatewayException("Slack OAuth response is missing workspace information.");
        }
        return new SlackWorkspace(team.id(), team.name(), accessToken);
    }

    /**
     * user 토큰 폐기 (연동 해제 시). 우리 DB에서 토큰을 지워도 Slack 쪽 권한 부여는 남으므로,
     * "해제하면 접근 권한이 끊긴다"를 실제로 만들려면 이 호출이 필요하다.
     *
     * <p>실패해도 예외를 던지지 않는다 — 이미 폐기된 토큰이거나 Slack 장애일 때 연동 해제
     * 자체가 막히면 사용자는 데이터를 지울 방법을 잃는다. 우리 쪽 토큰 삭제는 어차피 진행된다.</p>
     */
    public boolean revoke(String accessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", accessToken);

        try {
            SlackApiResponse response = restClient
                    .post()
                    .uri(properties.revokeUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SlackApiResponse.class);
            // Slack은 실패도 HTTP 200으로 응답한다 — ok 필드로 판별한다
            if (response == null || !Boolean.TRUE.equals(response.ok())) {
                // 이미 무효화된 토큰이면 지울 대상이 이미 없다는 뜻이므로 성공으로 취급한다
                if (response != null && response.error() != null
                        && ALREADY_REVOKED_ERRORS.contains(response.error())) {
                    return true;
                }
                log.warn("Slack token revoke failed. error={}",
                        response == null ? "empty_response" : response.error());
                return false;
            }
            return true;
        } catch (RestClientException exception) {
            log.warn("Slack token revoke request failed. error={}", exception.getMessage());
            return false;
        }
    }

    public record SlackWorkspace(String id, String name, String accessToken) {
    }

    private record SlackApiResponse(Boolean ok, String error) {
    }
}
