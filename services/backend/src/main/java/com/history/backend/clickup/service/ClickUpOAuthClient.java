package com.history.backend.clickup.service;

import com.history.backend.clickup.ClickUpProperties;
import com.history.backend.clickup.dto.ClickUpTokenRequest;
import com.history.backend.clickup.dto.ClickUpTokenResponse;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// ClickUp OAuth API 클라이언트 (authorization code → access token 교환). access token은
// 만료·회전·폐기가 없어 refresh·revoke 메서드가 없다(AsanaOAuthClient와 달리 code 교환 하나뿐이다).
// ClickUp 토큰 엔드포인트는 form-urlencoded가 아니라 JSON body를 받는다.
@Slf4j
@Component
public class ClickUpOAuthClient {

    private final ClickUpProperties properties;
    private final RestClient restClient;

    public ClickUpOAuthClient(
            ClickUpProperties properties,
            @Qualifier("clickUpRestClient")
            RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 access token으로 교환
    public ClickUpTokens exchangeCode(String code) {
        ClickUpTokenRequest request = new ClickUpTokenRequest(properties.clientId(), properties.clientSecret(), code);

        ClickUpTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ClickUpTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid ClickUp authorization code.");
            }
            throw new BadGatewayException("ClickUp OAuth code exchange request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("ClickUp OAuth code exchange request failed.", exception);
        }

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("ClickUp OAuth response is missing access token.");
        }
        return new ClickUpTokens(response.accessToken());
    }

    // 400·401·403만 확정된 인증 실패로 판정한다. 429(rate limit)·404 등 나머지 4xx와
    // 5xx는 ClickUp 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
    }

    public record ClickUpTokens(String accessToken) {
    }
}
