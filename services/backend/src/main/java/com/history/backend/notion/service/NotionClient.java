package com.history.backend.notion.service;

import java.util.Map;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.notion.NotionProperties;
import com.history.backend.notion.dto.NotionTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Notion OAuth API 클라이언트. code 교환·폐기 모두 Basic auth(client_id:client_secret)로 인증한다
// (docs/notion-integration.md §12-1·§12-5에서 확인). 요청 바디 형식(JSON vs 폼 인코딩)은 그 문서가
// 명시하지 않아 Notion 공개 API 문서 기준으로 JSON으로 구현했다 — 폼 인코딩을 쓰는
// Slack·Discord·Google Chat과 다른 지점이니 실기동 검증 시 우선 확인한다. 모든
// 요청에 Notion-Version 헤더를 싣는다 — 빠뜨리면 계정 기본 버전이 적용돼 배포 시점마다 응답
// 형태가 달라질 수 있다(NotionProperties.version).
@Slf4j
@Component
public class NotionClient {

    private final NotionProperties properties;
    private final RestClient restClient;

    public NotionClient(
            NotionProperties properties,
            @Qualifier("notionRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 access/refresh token과 워크스페이스 정보로 교환.
    public NotionAuthorization exchangeCode(String code) {
        Map<String, String> body = Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", properties.redirectUri()
        );

        NotionTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .headers(headers -> {
                        headers.setBasicAuth(properties.clientId(), properties.clientSecret());
                        headers.set("Notion-Version", properties.version());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(NotionTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Notion authorization code.");
            }
            throw new BadGatewayException("Notion OAuth code exchange request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Notion OAuth code exchange request failed.", exception);
        }

        return validateAuthorization(response);
    }

    /**
     * access token 폐기 (연동 해제 시). refresh_token이 아니라 access_token으로 호출한다(Notion API
     * 계약). 실패해도 예외를 던지지 않는다 — 이미 폐기된 토큰이거나 Notion 장애일 때 연동 해제
     * 자체가 막히면 사용자가 데이터를 지울 방법을 잃는다.
     */
    public boolean revoke(String accessToken) {
        Map<String, String> body = Map.of("token", accessToken);

        try {
            restClient
                    .post()
                    .uri(properties.revokeUrl())
                    .headers(headers -> {
                        headers.setBasicAuth(properties.clientId(), properties.clientSecret());
                        headers.set("Notion-Version", properties.version());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException exception) {
            log.warn("Notion token revoke request failed. error={}", exception.getMessage());
            return false;
        }
    }

    private NotionAuthorization validateAuthorization(NotionTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("Notion OAuth response is missing access token.");
        }
        if (response.workspaceId() == null || response.workspaceId().isBlank()
                || response.workspaceName() == null || response.workspaceName().isBlank()) {
            throw new BadGatewayException("Notion OAuth response is missing workspace information.");
        }
        if (response.botId() == null || response.botId().isBlank()) {
            throw new BadGatewayException("Notion OAuth response is missing bot id.");
        }
        return new NotionAuthorization(
                response.accessToken(), response.refreshToken(),
                response.workspaceId(), response.workspaceName(), response.botId());
    }

    // 400·401·403만 확정된 인증 실패로 판정한다(다른 provider client와 같은 기준).
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
    }

    public record NotionAuthorization(
            String accessToken, String refreshToken, String workspaceId, String workspaceName, String botId) {
    }
}
