package com.history.backend.linear.service;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.linear.LinearProperties;
import com.history.backend.linear.dto.LinearTokenResponse;
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

// Linear OAuth API 클라이언트 (authorization code → access/refresh token 교환, refresh token 갱신·폐기).
// Jira와 달리 토큰 엔드포인트가 JSON이 아니라 form-urlencoded body를 받는다(Slack과 같은 방식).
@Slf4j
@Component
public class LinearOAuthClient {

    private final LinearProperties properties;
    private final RestClient restClient;

    public LinearOAuthClient(
            LinearProperties properties,
            @Qualifier("linearRestClient")
            RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 access/refresh token으로 교환
    public LinearTokens exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        LinearTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(LinearTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Linear authorization code.");
            }
            throw new BadGatewayException("Linear OAuth code exchange request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Linear OAuth code exchange request failed.", exception);
        }

        return validateTokens(response);
    }

    // refresh token으로 access token 갱신. Linear도 Atlassian처럼 갱신할 때마다 새 refresh token을
    // 함께 내려주고 직전 것을 즉시 무효화한다 — 응답의 refreshToken()을 반드시 덮어써 저장해야 다음 갱신이 성공한다.
    public LinearTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        LinearTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(LinearTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                // refresh token이 폐기됨(재동의 취소·미사용) — 호출부(LinearTokenService)가 이 예외를 보고
                // pending 되돌리기를 판단한다.
                throw new UnauthorizedException("Linear refresh token is invalid or revoked.");
            }
            throw new BadGatewayException("Linear OAuth token refresh request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Linear OAuth token refresh request failed.", exception);
        }

        return validateTokens(response);
    }

    // refresh token 폐기 (연동 해제 시). 실패해도 예외를 던지지 않는다 — 이미 폐기됐거나 Linear
    // 장애일 때 연동 해제 자체가 막히면 안 된다.
    public boolean revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

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
            log.warn("Linear token revoke request failed. error={}", exception.getMessage());
            return false;
        }
    }

    // 400·401·403만 확정된 인증 실패로 판정한다. 429(rate limit)·404 등 나머지 4xx와
    // 5xx는 Linear 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
    }

    private LinearTokens validateTokens(LinearTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("Linear OAuth response is missing access token.");
        }
        if (response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BadGatewayException("Linear OAuth response is missing refresh token.");
        }
        // expiresIn이 박싱 Long이라 null이 그대로 넘어가면 호출부의 Instant.plusSeconds(...)에서
        // 자동 언박싱 NPE가 난다.
        if (response.expiresIn() == null) {
            throw new BadGatewayException("Linear OAuth response is missing expires_in.");
        }
        return new LinearTokens(response.accessToken(), response.refreshToken(), response.expiresIn());
    }

    public record LinearTokens(String accessToken, String refreshToken, Long expiresIn) {
    }
}
