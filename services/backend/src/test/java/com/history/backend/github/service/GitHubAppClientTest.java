package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubInstallationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("GitHubAppClient: GitHub App REST API 클라이언트")
class GitHubAppClientTest {

    @Mock
    private GitHubAppJwtService gitHubAppJwtService;

    @Test
    @DisplayName("App JWT로 installation 액세스 토큰 요청")
    void createInstallationAccessTokenRequestsTokenWithAppJwt() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer app-jwt"))
                .andRespond(withSuccess("""
                        {
                          "token": "installation-token",
                          "expires_at": "2026-05-19T01:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        InstallationAccessToken result = fixture.client.createInstallationAccessToken(98765L);

        assertThat(result.token()).isEqualTo("installation-token");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-05-19T01:00:00Z"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("빈 토큰 응답 시 예외 발생")
    void createInstallationAccessTokenRejectsEmptyTokenResponse() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765/access_tokens"))
                .andRespond(withSuccess("""
                        {
                          "token": "",
                          "expires_at": "2026-05-19T01:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.createInstallationAccessToken(98765L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub installation access token response is empty.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("빈 만료 시각 응답 시 예외 발생")
    void createInstallationAccessTokenRejectsEmptyExpiryResponse() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765/access_tokens"))
                .andRespond(withSuccess("""
                        {
                          "token": "installation-token",
                          "expires_at": ""
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.createInstallationAccessToken(98765L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub installation access token expiry is empty.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("유효하지 않은 만료 시각 응답 시 예외 발생")
    void createInstallationAccessTokenRejectsInvalidExpiryResponse() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765/access_tokens"))
                .andRespond(withSuccess("""
                        {
                          "token": "installation-token",
                          "expires_at": "not-an-instant"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.createInstallationAccessToken(98765L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub installation access token expiry is invalid.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("GitHub 오류 응답을 BadGatewayException으로 변환")
    void createInstallationAccessTokenWrapsGitHubErrors() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765/access_tokens"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.createInstallationAccessToken(98765L))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub installation access token request failed.")
                .hasMessageContaining("404");
        fixture.server.verify();
    }

    @Test
    @DisplayName("단일 부분 페이지 리포지토리 반환")
    void fetchInstallationRepositoriesReturnsSinglePartialPage() {
        GitHubAppClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/installation/repositories?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer installation-token"))
                .andRespond(withSuccess(repositoriesJson(99), MediaType.APPLICATION_JSON));

        var result = fixture.client.fetchInstallationRepositories("installation-token");

        assertThat(result).hasSize(99);
        assertThat(result.get(0).fullName()).isEqualTo("acme/repo-1");
        fixture.server.verify();
    }

    @Test
    @DisplayName("부분 페이지까지 모든 페이지 조회")
    void fetchInstallationRepositoriesFetchesUntilPartialPage() {
        GitHubAppClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/installation/repositories?per_page=100&page=1"))
                .andRespond(withSuccess(repositoriesJson(100), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo("https://api.github.test/installation/repositories?per_page=100&page=2"))
                .andRespond(withSuccess(repositoriesJson(50), MediaType.APPLICATION_JSON));

        var result = fixture.client.fetchInstallationRepositories("installation-token");

        assertThat(result).hasSize(150);
        fixture.server.verify();
    }

    @Test
    @DisplayName("리포지토리 조회 중 GitHub 오류를 BadGatewayException으로 변환")
    void fetchInstallationRepositoriesWrapsGitHubErrors() {
        GitHubAppClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/installation/repositories?per_page=100&page=1"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.fetchInstallationRepositories("installation-token"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub repository list request failed.")
                .hasMessageContaining("404");
        fixture.server.verify();
    }

    @Test
    @DisplayName("App JWT로 사용자 개인 installation 조회")
    void fetchUserInstallationRequestsWithAppJwtAndReturnsInstallation() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/users/octocat/installation"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer app-jwt"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andRespond(withSuccess("""
                        {
                          "id": 98765,
                          "account": {
                            "login": "octocat",
                            "type": "User"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<GitHubInstallationResponse> result = fixture.client.fetchUserInstallation("octocat");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(98765L);
        assertThat(result.get().account().login()).isEqualTo("octocat");
        assertThat(result.get().account().type()).isEqualTo("User");
        fixture.server.verify();
    }

    @Test
    @DisplayName("미설치(404)면 빈 Optional 반환")
    void fetchUserInstallationReturnsEmptyWhenNotInstalled() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/users/octocat/installation"))
                .andRespond(withResourceNotFound());

        Optional<GitHubInstallationResponse> result = fixture.client.fetchUserInstallation("octocat");

        assertThat(result).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("GitHub 오류 응답(5xx)을 BadGatewayException으로 변환")
    void fetchUserInstallationWrapsGitHubErrorsOnServerError() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/users/octocat/installation"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchUserInstallation("octocat"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("App JWT로 installation 단건 조회")
    void fetchInstallationRequestsWithAppJwtAndReturnsInstallation() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer app-jwt"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andRespond(withSuccess("""
                        {
                          "id": 98765,
                          "account": {
                            "login": "octocat",
                            "type": "User"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<GitHubInstallationResponse> result = fixture.client.fetchInstallation(98765L);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(98765L);
        assertThat(result.get().account().login()).isEqualTo("octocat");
        assertThat(result.get().account().type()).isEqualTo("User");
        fixture.server.verify();
    }

    @Test
    @DisplayName("설치 부재(404)면 빈 Optional 반환")
    void fetchInstallationReturnsEmptyWhenNotFound() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765"))
                .andRespond(withResourceNotFound());

        Optional<GitHubInstallationResponse> result = fixture.client.fetchInstallation(98765L);

        assertThat(result).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("GitHub 오류 응답(5xx)을 BadGatewayException으로 변환")
    void fetchInstallationWrapsGitHubErrorsOnServerError() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchInstallation(98765L))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private GitHubAppClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubAppClient client = new GitHubAppClient(properties(), gitHubAppJwtService, builder.build());
        return new GitHubAppClientFixture(client, server);
    }

    private String repositoriesJson(int count) {
        List<String> repositories = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> """
                        {
                          "id": %d,
                          "name": "repo-%d",
                          "full_name": "acme/repo-%d",
                          "owner": {"login": "acme"},
                          "private": true,
                          "visibility": "private",
                          "default_branch": "main"
                        }
                        """.formatted(index, index, index))
                .toList();
        return "{\"repositories\":[" + String.join(",", repositories) + "]}";
    }

    private GitHubAppProperties properties() {
        return new GitHubAppProperties(
                "123456",
                "history-tracker",
                "private-key",
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "https://github.com/apps/history-tracker/installations/new",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user",
                "https://api.github.com/user/installations",
                "https://api.github.test/app/installations/{installation_id}/access_tokens",
                "https://api.github.test/installation/repositories",
                "https://api.github.test/repos/{owner}/{repo}/branches",
                "https://api.github.test/user/installations/{installation_id}/repositories",
                "https://api.github.test/users/{username}/installation",
                "https://api.github.test/applications/{client_id}/grant",
                "https://api.github.test/app/installations/{installation_id}",
                Duration.ofMinutes(5)
        );
    }

    private record GitHubAppClientFixture(GitHubAppClient client, MockRestServiceServer server) {
    }
}
