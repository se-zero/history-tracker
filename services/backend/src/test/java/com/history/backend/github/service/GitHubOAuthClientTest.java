package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.history.backend.github.GitHubAppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("GitHubOAuthClient: GitHub OAuth 사용자 인증 API 클라이언트")
class GitHubOAuthClientTest {

    @Test
    @DisplayName("2xx 응답이면 installation에 접근 가능")
    void canAccessInstallationReturnsTrueOnSuccessResponse() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andRespond(withSuccess("""
                        { "repositories": [] }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.canAccessInstallation("user-access-token", 98765L);

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("403 응답이면 접근 불가로 처리")
    void canAccessInstallationReturnsFalseOnForbidden() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        boolean result = fixture.client.canAccessInstallation("user-access-token", 98765L);

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("404 응답이면 접근 불가로 처리")
    void canAccessInstallationReturnsFalseOnNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        boolean result = fixture.client.canAccessInstallation("user-access-token", 98765L);

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("5xx 응답이어도 예외를 던지지 않고 접근 불가로 처리")
    void canAccessInstallationReturnsFalseOnServerErrorWithoutThrowing() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withServerError());

        boolean result = fixture.client.canAccessInstallation("user-access-token", 98765L);

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    // RestClient.Builder에 mock 서버를 바인딩하고, 그 빌더로 만든 client를 반환
    private GitHubOAuthClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubOAuthClient client = new GitHubOAuthClient(properties(), builder.build());
        return new GitHubOAuthClientFixture(client, server);
    }

    private GitHubAppProperties properties() {
        return new GitHubAppProperties(
                "app-id",
                "history-tracker",
                "",
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.test/user",
                "https://api.github.test/user/installations",
                "https://api.github.test/app/installations/{installation_id}/access_tokens",
                "https://api.github.test/installation/repositories",
                "https://api.github.test/repos/{owner}/{repo}/branches",
                "https://api.github.test/user/installations/{installation_id}/repositories"
        );
    }

    private record GitHubOAuthClientFixture(GitHubOAuthClient client, MockRestServiceServer server) {
    }
}
