package com.history.backend.clickup.service;

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

import com.history.backend.clickup.ClickUpProperties;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// ClickUp OAuth 토큰 교환 클라이언트. access token은 만료·회전·폐기가 없어 refresh·revoke 메서드가 없다
// (AsanaOAuthClient와 달리 code 교환 하나뿐이다). ClickUp 토큰 엔드포인트는 form-urlencoded가 아니라
// JSON body를 받는다.
@DisplayName("ClickUpOAuthClient: ClickUp OAuth API 호출")
class ClickUpOAuthClientTest {

    @Test
    @DisplayName("code 교환 성공 → JSON 요청(client_id·client_secret·code), access_token 반환")
    void exchangeCodeReturnsAccessTokenWithJsonBody() {
        ClickUpOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "client_id": "test-clickup-client-id",
                          "client_secret": "test-clickup-client-secret",
                          "code": "auth-code"
                        }
                        """))
                .andRespond(withSuccess("""
                        { "access_token": "clickup-access-token" }
                        """, MediaType.APPLICATION_JSON));

        ClickUpOAuthClient.ClickUpTokens result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("clickup-access-token");
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 HTTP 400 응답 → UnauthorizedException 발생")
    void exchangeCodeMapsBadRequestToUnauthorized() {
        ClickUpOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/oauth/token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 HTTP 401 응답 → UnauthorizedException 발생")
    void exchangeCodeMapsUnauthorizedToUnauthorized() {
        ClickUpOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/oauth/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 HTTP 403 응답 → UnauthorizedException 발생")
    void exchangeCodeMapsForbiddenToUnauthorized() {
        ClickUpOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/oauth/token"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 중 ClickUp 5xx → BadGatewayException 발생 (인증 실패로 위장하지 않는다)")
    void exchangeCodeMapsServerErrorToBadGateway() {
        ClickUpOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/oauth/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답에 access_token 누락 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingAccessToken() {
        ClickUpOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/oauth/token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private ClickUpOAuthClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClickUpOAuthClient client = new ClickUpOAuthClient(
                new ClickUpProperties(
                        "test-clickup-client-id",
                        "test-clickup-client-secret",
                        "https://clickup.test/callback",
                        "https://api.clickup.com/api/v2/oauth/token"
                ),
                builder.build()
        );
        return new ClickUpOAuthClientFixture(client, server);
    }

    private record ClickUpOAuthClientFixture(ClickUpOAuthClient client, MockRestServiceServer server) {
    }
}
