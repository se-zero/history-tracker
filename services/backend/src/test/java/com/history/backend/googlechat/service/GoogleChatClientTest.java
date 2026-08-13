package com.history.backend.googlechat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.time.Duration;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.googlechat.GoogleChatProperties;
import com.history.backend.googlechat.dto.GoogleChatSpaceListResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("GoogleChatClient: Google OAuth2 / Chat API 호출")
class GoogleChatClientTest {

    @Test
    @DisplayName("code 교환 성공 → access/refresh token·만료초 반환")
    void exchangeCodeReturnsTokens() {
        Fixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add("code", "auth-code");
        expectedForm.add("redirect_uri", "https://googlechat.test/callback");

        fixture.server.expect(once(), requestTo("https://googlechat.test/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "access_token": "access-token",
                          "refresh_token": "refresh-token",
                          "expires_in": 3599
                        }
                        """, MediaType.APPLICATION_JSON));

        GoogleChatClient.GoogleChatTokens result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.expiresIn()).isEqualTo(3599L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 401 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsUnauthorizedAsUnauthorized() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://googlechat.test/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Google Chat authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 5xx 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void exchangeCodeRejectsServerErrorAsBadGateway() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://googlechat.test/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh_token 누락 응답 → BadGatewayException 발생 (access_type=offline+prompt=consent 누락 신호)")
    void exchangeCodeRejectsMissingRefreshToken() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://googlechat.test/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "access-token",
                          "expires_in": 3599
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Google Chat OAuth response is missing refresh token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("갱신 응답은 refresh_token 없이도 성공 — Google은 회전하지 않아 재발급하지 않는다")
    void refreshSucceedsWithoutRefreshTokenInResponse() {
        Fixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "refresh_token");
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add("refresh_token", "stored-refresh-token");

        fixture.server.expect(once(), requestTo("https://googlechat.test/token"))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "expires_in": 3599
                        }
                        """, MediaType.APPLICATION_JSON));

        GoogleChatClient.GoogleChatTokens result = fixture.client.refresh("stored-refresh-token");

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("갱신 시 HTTP 401 응답 → UnauthorizedException(폐기 판정)")
    void refreshRejectsUnauthorizedAsUnauthorized() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://googlechat.test/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Google Chat refresh token is invalid or revoked.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 요청은 token 파라미터만 담아 보낸다")
    void revokeSendsToken() {
        Fixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("token", "refresh-token");

        fixture.server.expect(once(), requestTo("https://googlechat.test/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        fixture.client.revoke("refresh-token");
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 요청이 실패해도 예외를 던지지 않는다 — 연동 해제 자체가 막히면 안 된다")
    void revokeSwallowsFailure() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://googlechat.test/revoke"))
                .andRespond(withServerError());

        assertThatCode(() -> fixture.client.revoke("refresh-token")).doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    @DisplayName("스페이스 목록은 spaceType=SPACE 필터로 조회하고 Bearer 토큰을 담는다")
    void listSpacesFiltersToNamedSpacesOnly() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://googlechat.test/v1/spaces?filter=spaceType%20%3D%20%22SPACE%22&pageSize=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andRespond(withSuccess("""
                        {
                          "spaces": [
                            { "name": "spaces/AAAA", "displayName": "engineering" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<GoogleChatSpaceListResponse.GoogleChatSpace> result = fixture.client.listSpaces("user-access-token");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("spaces/AAAA");
        assertThat(result.get(0).displayName()).isEqualTo("engineering");
        fixture.server.verify();
    }

    @Test
    @DisplayName("nextPageToken이 있으면 이어서 다음 페이지를 요청해 모두 합친다")
    void listSpacesFollowsPagination() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://googlechat.test/v1/spaces?filter=spaceType%20%3D%20%22SPACE%22&pageSize=100"))
                .andRespond(withSuccess("""
                        {
                          "spaces": [ { "name": "spaces/AAAA", "displayName": "engineering" } ],
                          "nextPageToken": "page-2"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(
                        "https://googlechat.test/v1/spaces?filter=spaceType%20%3D%20%22SPACE%22&pageSize=100&pageToken=page-2"))
                .andRespond(withSuccess("""
                        {
                          "spaces": [ { "name": "spaces/BBBB", "displayName": "design" } ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<GoogleChatSpaceListResponse.GoogleChatSpace> result = fixture.client.listSpaces("user-access-token");

        assertThat(result).extracting(GoogleChatSpaceListResponse.GoogleChatSpace::name)
                .containsExactly("spaces/AAAA", "spaces/BBBB");
        fixture.server.verify();
    }

    @Test
    @DisplayName("스페이스 목록 조회 시 HTTP 401 → UnauthorizedException")
    void listSpacesRejectsUnauthorizedAsUnauthorized() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://googlechat.test/v1/spaces?filter=spaceType%20%3D%20%22SPACE%22&pageSize=100"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.listSpaces("expired-access-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Google Chat access token.");
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleChatClient client = new GoogleChatClient(
                new GoogleChatProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://googlechat.test/callback",
                        "https://www.googleapis.com/auth/chat.spaces.readonly https://www.googleapis.com/auth/chat.messages.readonly",
                        "https://googlechat.test/o/oauth2/v2/auth",
                        "https://googlechat.test/token",
                        "https://googlechat.test/revoke",
                        "https://googlechat.test/v1",
                        Duration.ofMinutes(5)
                ),
                builder.build()
        );
        return new Fixture(client, server);
    }

    private record Fixture(GoogleChatClient client, MockRestServiceServer server) {
    }
}
