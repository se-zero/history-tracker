package com.history.backend.googlechat.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.googlechat.GoogleChatProperties;
import com.history.backend.googlechat.dto.GoogleChatSpaceListResponse;
import com.history.backend.googlechat.dto.GoogleChatTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

// Google OAuth2 / Chat API 클라이언트. 토큰 엔드포인트(oauth2.googleapis.com)와 리소스 엔드포인트
// (chat.googleapis.com)가 서로 다른 호스트라 URL을 프로퍼티별로 완전한 절대경로로 둔다(Jira·Discord와
// 같은 방식) — RestClient 빈에는 baseUrl을 두지 않는다.
@Slf4j
@Component
public class GoogleChatClient {

    private static final int PAGE_SIZE = 100;

    private final GoogleChatProperties properties;
    private final RestClient restClient;

    public GoogleChatClient(
            GoogleChatProperties properties,
            @Qualifier("googleChatRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 access/refresh token으로 교환.
    public GoogleChatTokens exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());

        GoogleChatTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleChatTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Google Chat authorization code.");
            }
            throw new BadGatewayException("Google Chat OAuth code exchange request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Google Chat OAuth code exchange request failed.", exception);
        }

        return validateInitialTokens(response);
    }

    /**
     * refresh token으로 access token 갱신. Google은 갱신 응답에 refresh_token을 다시 내려주지
     * 않는다(회전하지 않음) — 호출부({@code GoogleChatTokenService})가 기존 refresh token을
     * 보존해야 한다. 여기서는 응답을 그대로 반환만 하고 보존 책임은 지지 않는다.
     */
    public GoogleChatTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("refresh_token", refreshToken);

        GoogleChatTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleChatTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                // refresh token이 폐기됨(사용자가 access 취소·6개월 미사용·Testing 상태 7일 만료) —
                // 호출부(GoogleChatTokenService)가 이 예외를 보고 pending 되돌리기를 판단한다.
                throw new UnauthorizedException("Google Chat refresh token is invalid or revoked.");
            }
            throw new BadGatewayException("Google Chat OAuth token refresh request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Google Chat OAuth token refresh request failed.", exception);
        }

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("Google Chat OAuth response is missing access token.");
        }
        if (response.expiresIn() == null) {
            throw new BadGatewayException("Google Chat OAuth response is missing expires_in.");
        }
        return new GoogleChatTokens(response.accessToken(), response.refreshToken(), response.expiresIn());
    }

    /**
     * OAuth grant 폐기 (연동 해제 시). refresh token 하나로 그로부터 파생된 access token도 함께
     * 무효화된다.
     *
     * <p>Slack·Jira·Discord와 같은 이유로 실패해도 예외를 던지지 않는다 — 이미 폐기됐거나 Google
     * 장애일 때 연동 해제 자체가 막히면 안 된다.</p>
     */
    public boolean revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
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
            log.warn("Google Chat token revoke request failed. error={}", exception.getMessage());
            return false;
        }
    }

    /**
     * 선택 단계 후보 조회. {@code spaceType = "SPACE"}로 걸러 DM·그룹챗을 제외한다 — 이름 있는
     * 스페이스만 후보로 올려야 개인 대화가 프로젝트 그래프에 섞이지 않는다.
     */
    public List<GoogleChatSpaceListResponse.GoogleChatSpace> listSpaces(String accessToken) {
        List<GoogleChatSpaceListResponse.GoogleChatSpace> spaces = new ArrayList<>();
        String pageToken = null;
        do {
            GoogleChatSpaceListResponse response = fetchSpacesPage(accessToken, pageToken);
            if (response != null && response.spaces() != null) {
                spaces.addAll(response.spaces());
            }
            pageToken = response == null ? null : response.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return spaces;
    }

    private GoogleChatSpaceListResponse fetchSpacesPage(String accessToken, String pageToken) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(properties.apiBaseUrl() + "/spaces")
                .queryParam("filter", "spaceType = \"SPACE\"")
                .queryParam("pageSize", PAGE_SIZE);
        if (pageToken != null && !pageToken.isBlank()) {
            uriBuilder.queryParam("pageToken", pageToken);
        }
        // build() 후 encode()로 UriComponents 단계에서 인코딩을 끝내고 URI로 변환한다 — 문자열로
        // 뽑아 RestClient.uri(String)에 넘기면 RestClient가 다시 한 번 인코딩해 이중 인코딩된다
        // (%20이 %2520이 되는 사고). URI 객체로 넘기면 RestClient가 있는 그대로 쓴다.
        URI uri = uriBuilder.build().encode().toUri();

        try {
            return restClient
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleChatSpaceListResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Google Chat access token.");
            }
            throw new BadGatewayException("Google Chat spaces list request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Google Chat spaces list request failed.", exception);
        }
    }

    private static GoogleChatTokens validateInitialTokens(GoogleChatTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BadGatewayException("Google Chat OAuth response is missing access token.");
        }
        // access_type=offline + prompt=consent를 안 넣으면 refresh_token 없이 응답한다 — 여기서
        // 막지 않으면 null로 조용히 저장되고 첫 갱신 시점에서야 "갱신 불가"로 드러난다.
        if (response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BadGatewayException("Google Chat OAuth response is missing refresh token.");
        }
        if (response.expiresIn() == null) {
            throw new BadGatewayException("Google Chat OAuth response is missing expires_in.");
        }
        return new GoogleChatTokens(response.accessToken(), response.refreshToken(), response.expiresIn());
    }

    // 400·401·403만 확정된 인증 실패로 판정한다(JiraOAuthClient·DiscordClient와 같은 기준).
    // 나머지 4xx·5xx는 Google 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
    }

    public record GoogleChatTokens(String accessToken, String refreshToken, Long expiresIn) {
    }
}
