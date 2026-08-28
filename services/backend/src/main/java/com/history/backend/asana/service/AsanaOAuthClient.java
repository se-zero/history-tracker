package com.history.backend.asana.service;

import com.history.backend.asana.AsanaProperties;
import com.history.backend.asana.dto.AsanaTokenResponse;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Asana OAuth API 클라이언트 (authorization code → access/refresh token 교환, refresh token 갱신·폐기).
// Linear·Jira와 달리 갱신(refresh_token grant) 응답은 refresh_token을 회전하지 않는다 — 교환 응답에서만
// 필수로 검증하고, 갱신 응답에서는 null을 허용한다(아래 validateExchangeTokens/validateRefreshTokens 참고).
@Slf4j
@Component
public class AsanaOAuthClient {

    private final AsanaProperties properties;
    private final RestClient restClient;

    public AsanaOAuthClient(
            AsanaProperties properties,
            @Qualifier("asanaRestClient")
            RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 access/refresh token으로 교환
    public AsanaTokens exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        AsanaTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AsanaTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Asana authorization code.");
            }
            throw new BadGatewayException("Asana OAuth code exchange request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Asana OAuth code exchange request failed.", exception);
        }

        return validateExchangeTokens(response);
    }

    // refresh token으로 access token 갱신. Asana는 갱신할 때마다 refresh_token을 다시 내려주지 않으므로
    // 응답에 없으면 null을 그대로 반환한다 — 필수로 검증하면 매 갱신이 실패한다.
    public AsanaTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        AsanaTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AsanaTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                // refresh token이 폐기됨(재동의 취소·미사용) — 호출부(AsanaTokenService)가 이 예외를 보고
                // pending 되돌리기를 판단한다.
                throw new UnauthorizedException("Asana refresh token is invalid or revoked.");
            }
            throw new BadGatewayException("Asana OAuth token refresh request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Asana OAuth token refresh request failed.", exception);
        }

        return validateRefreshTokens(response);
    }

    // refresh token 폐기 (연동 해제 시). access token이 아니라 refresh token을 보내야 한다 — Asana는
    // access token으로 호출하면 거부한다. 실패해도 예외를 던지지 않는다 — 이미 폐기됐거나 Asana
    // 장애일 때 연동 해제 자체가 막히면 안 된다.
    public boolean revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("token", refreshToken);

        try {
            restClient
                    .post()
                    .uri(properties.revokeUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException exception) {
            log.warn("Asana token revoke request failed. error={}", exception.getMessage());
            return false;
        }
    }

    // 400·401·403만 확정된 인증 실패로 판정한다. 429(rate limit)·404 등 나머지 4xx와
    // 5xx는 Asana 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
    }

    // code 교환 응답 검증 — refresh_token이 반드시 있어야 이후 갱신이 가능하다
    private AsanaTokens validateExchangeTokens(AsanaTokenResponse response) {
        AsanaTokens tokens = validateCommonFields(response);
        if (response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BadGatewayException("Asana OAuth response is missing refresh token.");
        }
        return tokens;
    }

    // refresh 응답 검증 — Asana는 갱신 응답에 refresh_token을 내려주지 않으므로 null을 허용한다
    private AsanaTokens validateRefreshTokens(AsanaTokenResponse response) {
        return validateCommonFields(response);
    }

    private AsanaTokens validateCommonFields(AsanaTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("Asana OAuth response is missing access token.");
        }
        // expiresIn이 박싱 Long이라 null이 그대로 넘어가면 호출부의 Instant.plusSeconds(...)에서
        // 자동 언박싱 NPE가 난다.
        if (response.expiresIn() == null) {
            throw new BadGatewayException("Asana OAuth response is missing expires_in.");
        }
        return new AsanaTokens(response.accessToken(), response.refreshToken(), response.expiresIn());
    }

    public record AsanaTokens(String accessToken, String refreshToken, Long expiresIn) {
    }
}
