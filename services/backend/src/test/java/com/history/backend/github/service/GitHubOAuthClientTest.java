package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

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
    @DisplayName("2xx 응답이면 ACCESSIBLE")
    void checkInstallationAccessReturnsAccessibleOnSuccessResponse() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andRespond(withSuccess("""
                        { "repositories": [] }
                        """, MediaType.APPLICATION_JSON));

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.ACCESSIBLE);
        fixture.server.verify();
    }

    @Test
    @DisplayName("403 응답이면 DENIED — 진짜 접근 없음")
    void checkInstallationAccessReturnsDeniedOnForbidden() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.DENIED);
        fixture.server.verify();
    }

    @Test
    @DisplayName("404 응답이면 DENIED — 진짜 접근 없음")
    void checkInstallationAccessReturnsDeniedOnNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.DENIED);
        fixture.server.verify();
    }

    @Test
    @DisplayName("500 응답이면 UNKNOWN — 접근 없음과 구분해 판단 보류")
    void checkInstallationAccessReturnsUnknownOnServerError() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withServerError());

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.UNKNOWN);
        fixture.server.verify();
    }

    @Test
    @DisplayName("네트워크 예외(타임아웃 등)면 UNKNOWN — 접근 없음과 구분해 판단 보류")
    void checkInstallationAccessReturnsUnknownOnNetworkException() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.UNKNOWN);
        fixture.server.verify();
    }

    @Test
    @DisplayName("installation 목록 조회는 per_page=100으로 요청해 기본 30개 절단을 막는다")
    void fetchInstallationsRequestsWithPerPage100() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andRespond(withSuccess("""
                        { "installations": [] }
                        """, MediaType.APPLICATION_JSON));

        fixture.client.fetchInstallations("user-access-token");

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
