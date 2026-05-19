package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.github.GitHubAppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class GitHubAppClientTest {

    @Mock
    private GitHubAppJwtService gitHubAppJwtService;

    @Test
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

        String result = fixture.client.createInstallationAccessToken(98765L);

        assertThat(result).isEqualTo("installation-token");
        fixture.server.verify();
    }

    @Test
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
                "https://api.github.test/installation/repositories"
        );
    }

    private record GitHubAppClientFixture(GitHubAppClient client, MockRestServiceServer server) {
    }
}
