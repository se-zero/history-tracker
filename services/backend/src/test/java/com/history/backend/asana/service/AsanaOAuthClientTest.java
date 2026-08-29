package com.history.backend.asana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.time.Duration;

import com.history.backend.asana.AsanaProperties;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("AsanaOAuthClient: Asana OAuth API 호출")
class AsanaOAuthClientTest {

    @Test
    @DisplayName("code 교환 성공 → form-urlencoded 요청, 토큰 3종 반환")
    void exchangeCodeReturnsTokensWithFormEncodedBody() {
        AsanaOAuthClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("code", "auth-code");
        expectedForm.add("redirect_uri", "https://asana.test/callback");
        expectedForm.add("client_id", "test-asana-client-id");
        expectedForm.add("client_secret", "test-asana-client-secret");

        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "access_token": "asana-access-token",
                          "refresh_token": "asana-refresh-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        AsanaOAuthClient.AsanaTokens result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("asana-access-token");
        assertThat(result.refreshToken()).isEqualTo("asana-refresh-token");
        // access token 1시간 만료
        assertThat(result.expiresIn()).isEqualTo(3600L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답에 refresh_token 누락 → BadGatewayException 발생 (교환은 refresh 필수)")
    void exchangeCodeRejectsMissingRefreshToken() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "asana-access-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 HTTP 400 응답 → UnauthorizedException 발생")
    void exchangeCodeMapsBadRequestToUnauthorized() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 HTTP 401 응답 → UnauthorizedException 발생")
    void exchangeCodeMapsUnauthorizedToUnauthorized() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 HTTP 403 응답 → UnauthorizedException 발생")
    void exchangeCodeMapsForbiddenToUnauthorized() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 중 Asana 5xx → BadGatewayException 발생 (인증 실패로 위장하지 않는다)")
    void exchangeCodeMapsServerErrorToBadGateway() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withServerError());

        // Asana 측 일시 장애가 "잘못된 인증 코드"(401)로 보고되면 원인 진단이 오도된다
        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 refresh_token 누락 → 정상 처리, refreshToken은 null (Asana는 갱신 시 회전하지 않는다)")
    void refreshReturnsTokensWithFormEncodedBodyAndNullRefreshTokenWhenAbsent() {
        // Linear·Jira와 달리 Asana는 refresh_token grant 응답에 refresh_token을 내려주지 않는다.
        // 여기서 필수로 잘못 검증하면 매 갱신이 BadGatewayException으로 실패한다.
        AsanaOAuthClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "refresh_token");
        expectedForm.add("refresh_token", "old-refresh-token");
        expectedForm.add("client_id", "test-asana-client-id");
        expectedForm.add("client_secret", "test-asana-client-secret");

        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        AsanaOAuthClient.AsanaTokens result = fixture.client.refresh("old-refresh-token");

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isNull();
        assertThat(result.expiresIn()).isEqualTo(3600L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 refresh_token이 있으면 그대로 전달")
    void refreshPropagatesRefreshTokenWhenPresent() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "refresh_token": "unexpected-rotated-refresh-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        AsanaOAuthClient.AsanaTokens result = fixture.client.refresh("old-refresh-token");

        assertThat(result.refreshToken()).isEqualTo("unexpected-rotated-refresh-token");
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 갱신 HTTP 400 응답 → UnauthorizedException 발생")
    void refreshMapsBadRequestToUnauthorized() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 갱신 HTTP 401 응답 → UnauthorizedException 발생")
    void refreshMapsUnauthorizedToUnauthorized() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 갱신 HTTP 403 응답 → UnauthorizedException 발생")
    void refreshMapsForbiddenToUnauthorized() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 Asana 5xx → BadGatewayException 발생 (폐기로 오판하지 않는다)")
    void refreshMapsServerErrorToBadGateway() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기 → revoke URL로 form-urlencoded 전송 (client_id·client_secret·token 3필드), 성공하면 true를 반환한다")
    void revokeSendsFormEncodedRequestToRevokeUrlAndReturnsTrueOnSuccess() {
        AsanaOAuthClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("client_id", "test-asana-client-id");
        expectedForm.add("client_secret", "test-asana-client-secret");
        expectedForm.add("token", "refresh-token");

        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        boolean result = fixture.client.revoke("refresh-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("폐기 요청이 실패해도 예외를 던지지 않고 false를 반환한다 (이미 폐기된 토큰·Asana 장애를 해제 실패로 만들지 않는다)")
    void revokeReturnsFalseWhenRequestFails() {
        AsanaOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/-/oauth_revoke"))
                .andRespond(withServerError());

        boolean result = fixture.client.revoke("refresh-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    private AsanaOAuthClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AsanaOAuthClient client = new AsanaOAuthClient(
                new AsanaProperties(
                        "test-asana-client-id",
                        "test-asana-client-secret",
                        "https://asana.test/callback",
                        "https://app.asana.com/-/oauth_token",
                        "https://app.asana.com/-/oauth_revoke",
                        Duration.ofMinutes(30)
                ),
                builder.build()
        );
        return new AsanaOAuthClientFixture(client, server);
    }

    private record AsanaOAuthClientFixture(AsanaOAuthClient client, MockRestServiceServer server) {
    }
}
