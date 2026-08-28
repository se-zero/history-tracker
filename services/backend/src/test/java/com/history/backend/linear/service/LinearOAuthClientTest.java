package com.history.backend.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.linear.LinearProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("LinearOAuthClient: Linear OAuth API 호출")
class LinearOAuthClientTest {

    @Test
    @DisplayName("code 교환 성공 → form-urlencoded 요청, 토큰 3종 반환")
    void exchangeCodeReturnsTokensWithFormEncodedBody() {
        LinearOAuthClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("code", "auth-code");
        expectedForm.add("redirect_uri", "https://linear.test/callback");
        expectedForm.add("client_id", "test-linear-client-id");
        expectedForm.add("client_secret", "test-linear-client-secret");

        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "access_token": "linear-access-token",
                          "refresh_token": "linear-refresh-token",
                          "expires_in": 86400,
                          "token_type": "Bearer",
                          "scope": "read,write"
                        }
                        """, MediaType.APPLICATION_JSON));

        LinearOAuthClient.LinearTokens result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("linear-access-token");
        assertThat(result.refreshToken()).isEqualTo("linear-refresh-token");
        // access token 24h 만료
        assertThat(result.expiresIn()).isEqualTo(86400L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 401 → UnauthorizedException (확정 인증 실패)")
    void exchangeCodeMapsDefiniteAuthFailureToUnauthorized() {
        LinearOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("code 교환 중 Linear 5xx → BadGatewayException (인증 실패로 위장하지 않는다)")
    void exchangeCodeMapsServerErrorToBadGateway() {
        LinearOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andRespond(withServerError());

        // Linear 측 일시 장애가 "잘못된 인증 코드"(401)로 보고되면 원인 진단이 오도된다
        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
    }

    @Test
    @DisplayName("refresh token 갱신 성공 → form-urlencoded 요청, 회전된 새 토큰 3종 반환")
    void refreshReturnsRotatedTokensWithFormEncodedBody() {
        LinearOAuthClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "refresh_token");
        expectedForm.add("refresh_token", "old-refresh-token");
        expectedForm.add("client_id", "test-linear-client-id");
        expectedForm.add("client_secret", "test-linear-client-secret");

        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "refresh_token": "rotated-refresh-token",
                          "expires_in": 86400
                        }
                        """, MediaType.APPLICATION_JSON));

        LinearOAuthClient.LinearTokens result = fixture.client.refresh("old-refresh-token");

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        // 응답에 담겨 오는 새 refresh token(회전) — 저장하지 않으면 다음 갱신이 영구 실패한다
        assertThat(result.refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(result.expiresIn()).isEqualTo(86400L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 401) → UnauthorizedException 발생")
    void refreshRejectsDefiniteAuthFailureAsUnauthorized() {
        LinearOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 Linear 5xx → BadGatewayException 발생 (폐기로 오판하지 않는다)")
    void refreshRejectsServerErrorAsBadGateway() {
        LinearOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 refresh_token 누락 → BadGatewayException 발생")
    void refreshRejectsMissingRotatedRefreshToken() {
        LinearOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "expires_in": 86400
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기 → revoke URL로 form-urlencoded 전송 (token_type_hint=refresh_token), 성공하면 true를 반환한다")
    void revokeSendsFormEncodedRequestToRevokeUrlAndReturnsTrueOnSuccess() {
        LinearOAuthClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("token", "refresh-token");
        expectedForm.add("token_type_hint", "refresh_token");
        expectedForm.add("client_id", "test-linear-client-id");
        expectedForm.add("client_secret", "test-linear-client-secret");

        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        boolean result = fixture.client.revoke("refresh-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("폐기 요청이 실패해도 예외를 던지지 않고 false를 반환한다 (이미 폐기된 토큰·Linear 장애를 해제 실패로 만들지 않는다)")
    void revokeReturnsFalseWhenRequestFails() {
        LinearOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/oauth/revoke"))
                .andRespond(withServerError());

        boolean result = fixture.client.revoke("refresh-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    private LinearOAuthClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinearOAuthClient client = new LinearOAuthClient(
                new LinearProperties(
                        "test-linear-client-id",
                        "test-linear-client-secret",
                        "https://linear.test/callback",
                        "https://api.linear.app/oauth/token",
                        "https://api.linear.app/oauth/revoke",
                        Duration.ofMinutes(30)
                ),
                builder.build()
        );
        return new LinearOAuthClientFixture(client, server);
    }

    private record LinearOAuthClientFixture(LinearOAuthClient client, MockRestServiceServer server) {
    }
}
