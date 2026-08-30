package com.history.backend.slack.service;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.slack.SlackProperties;
import com.history.backend.slack.dto.SlackAuthTestResponse;
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

// Slack OAuth API 클라이언트 (authorization code → user 토큰·선택적 bot 토큰 교환)
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

    // authorization code를 user 토큰·선택적 bot 토큰·authed user id·workspace로 교환.
    // bot scope가 없으면 루트 access_token이 오지 않는다 — 그 경우 botToken은 null이다.
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

        String userToken = response.authedUser() == null ? null : response.authedUser().accessToken();
        if (userToken == null || userToken.isBlank()) {
            throw new BadGatewayException("Slack OAuth response is missing user access token.");
        }
        String botToken = (response.accessToken() != null && !response.accessToken().isBlank())
                ? response.accessToken() : null;
        String authedUserId = response.authedUser().id();
        if (authedUserId == null || authedUserId.isBlank()) {
            throw new BadGatewayException("Slack OAuth response is missing authed user id.");
        }
        SlackOAuthAccessResponse.Team team = response.team();
        if (team == null || team.id() == null || team.id().isBlank() || team.name() == null || team.name().isBlank()) {
            throw new BadGatewayException("Slack OAuth response is missing workspace information.");
        }
        return new SlackWorkspace(team.id(), team.name(), userToken, botToken, authedUserId);
    }

    /**
     * user 또는 bot 토큰 폐기 (연동 해제 시). 호출부가 어떤 토큰을 넘기든 동일하다.
     * 우리 DB에서 토큰을 지워도 Slack 쪽 권한 부여는 남으므로,
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

    // 레거시 행 게이팅용 — SlackProperties URL이 아니라 고정 auth.test 엔드포인트다.
    // 실패해도 커맨드 전체를 죽이지 않기 위해 예외를 삼키고 null을 반환한다.
    public String authTest(String userToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", userToken);
        try {
            SlackApiResponse response = restClient
                    .post()
                    .uri("https://slack.com/api/auth.test")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SlackApiResponse.class);
            if (response == null || !Boolean.TRUE.equals(response.ok())) {
                return null;
            }
            String userId = response.userId();
            return userId == null || userId.isBlank() ? null : userId;
        } catch (RestClientException exception) {
            log.warn("Slack auth.test request failed. error={}", exception.getMessage());
            return null;
        }
    }

    // BYO 연결은 사용자에게 실패 이유를 돌려줘야 해서, 커맨드 백필용 authTest와 달리 예외를 삼키지 않는다.
    public SlackVerifiedUser verifyToken(String token) {
        String trimmed = token.trim();
        if (!trimmed.startsWith("xoxp-")) {
            throw new UnauthorizedException("Invalid Slack token.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", trimmed);

        SlackAuthTestResponse response;
        try {
            response = restClient
                    .post()
                    .uri("https://slack.com/api/auth.test")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SlackAuthTestResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new UnauthorizedException("Invalid Slack token.");
            }
            throw new BadGatewayException("Slack auth test request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Slack auth test request failed.", exception);
        }

        if (response == null || !Boolean.TRUE.equals(response.ok())) {
            throw new UnauthorizedException("Invalid Slack token.");
        }
        // bot_id가 있으면 봇 토큰이다 — 사용자 xoxp만 받는다
        if (response.botId() != null && !response.botId().isBlank()) {
            throw new UnauthorizedException("Invalid Slack token.");
        }
        String teamId = response.teamId();
        String teamName = response.team();
        if (teamId == null || teamId.isBlank() || teamName == null || teamName.isBlank()) {
            throw new BadGatewayException("Slack auth test response is missing workspace information.");
        }
        String userId = response.userId();
        if (userId == null || userId.isBlank()) {
            throw new BadGatewayException("Slack auth test response is missing authed user id.");
        }
        return new SlackVerifiedUser(teamId, teamName, userId);
    }

    // response_url 유효 시간(30분) 안에만 도달하면 되므로, 실패해도 커맨드 ack는 이미 끝난 뒤다.
    public void postEphemeral(String responseUrl, String text) {
        try {
            restClient
                    .post()
                    .uri(responseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SlackCommandAck("ephemeral", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("Slack response_url post failed. error={}", exception.getMessage());
        }
    }

    public record SlackWorkspace(String id, String name, String userToken, String botToken, String authedUserId) {
    }

    public record SlackVerifiedUser(String teamId, String teamName, String userId) {
    }

    private record SlackApiResponse(
            Boolean ok,
            String error,
            @JsonProperty("user_id") String userId
    ) {
    }
}
