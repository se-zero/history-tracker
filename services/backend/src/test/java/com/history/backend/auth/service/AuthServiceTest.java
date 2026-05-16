package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.RefreshTokenRequest;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationAccountResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.GitHubOAuthClient;
import com.history.backend.security.JwtProperties;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final GitHubAppProperties GITHUB_PROPERTIES = new GitHubAppProperties(
            "client-id",
            "client-secret",
            "http://localhost/api/v1/auth/github/callback",
            "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "https://api.github.com/user/installations"
    );

    private static final JwtProperties JWT_PROPERTIES = new JwtProperties(
            "jwt-secret",
            Duration.ofMinutes(15),
            Duration.ofDays(14)
    );

    @Mock
    private GitHubOAuthClient gitHubOAuthClient;

    @Mock
    private GitHubInstallationService gitHubInstallationService;

    @Mock
    private UserService userService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtTokenService jwtTokenService;

    @Test
    void buildGitHubAuthorizeUri() {
        AuthService authService = authService();

        URI uri = authService.buildGitHubAuthorizeUri("state-123");

        assertThat(uri.toString())
                .isEqualTo("https://github.com/login/oauth/authorize"
                        + "?client_id=client-id"
                        + "&redirect_uri=http://localhost/api/v1/auth/github/callback"
                        + "&state=state-123");
    }

    @Test
    void loginWithGitHubIssuesTokenResponse() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        verify(gitHubOAuthClient).fetchUser("github-user-token");
    }

    @Test
    void loginWithGitHubPersistsInstallationWhenCallbackHasInstallationId() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse installation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("acme", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(installation)));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, 98765L));

        verify(gitHubInstallationService).upsertInstallation(user, installation);
    }

    @Test
    void refreshRotatesRefreshTokenAndIssuesAccessToken() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);

        when(refreshTokenService.rotateRefreshToken("old-refresh-token"))
                .thenReturn(new RefreshTokenIssue(user, "new-refresh-token"));
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("new-access-token");

        var response = authService.refresh(new RefreshTokenRequest("old-refresh-token"));

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
    }

    @Test
    void logoutRevokesRefreshToken() {
        AuthService authService = authService();

        authService.logout(new RefreshTokenRequest("refresh-token"));

        verify(refreshTokenService).revokeRefreshToken("refresh-token");
    }

    private AuthService authService() {
        return new AuthService(
                GITHUB_PROPERTIES,
                gitHubOAuthClient,
                gitHubInstallationService,
                userService,
                refreshTokenService,
                jwtTokenService,
                JWT_PROPERTIES
        );
    }
}
