package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.RefreshTokenRequest;
import com.history.backend.common.error.UnauthorizedException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService: GitHub OAuth 로그인·토큰 갱신·로그아웃")
class AuthServiceTest {

    private static final GitHubAppProperties GITHUB_PROPERTIES = new GitHubAppProperties(
            "app-id",
            "history-tracker",
            "",
            "client-id",
            "client-secret",
            "http://localhost/api/v1/auth/github/callback",
            "",
            "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "https://api.github.com/user/installations",
            "https://api.github.com/app/installations/{installation_id}/access_tokens",
            "https://api.github.com/installation/repositories",
            "https://api.github.com/repos/{owner}/{repo}/branches"
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
    @DisplayName("GitHub OAuth authorize URI 생성")
    void buildGitHubAuthorizeUri() {
        AuthService authService = authService();

        URI uri = authService.buildGitHubAuthorizeUri("foo&bar=baz");

        assertThat(uri.toString())
                .startsWith("https://github.com/login/oauth/authorize")
                .contains("client_id=client-id")
                .contains("redirect_uri=http://localhost/api/v1/auth/github/callback")
                .contains("state=foo%26bar%3Dbaz");
    }

    @Test
    @DisplayName("installation URL 미설정 시 app slug로 설치 URI 생성")
    void buildGitHubInstallUriDerivesFromAppSlugWhenInstallationUrlNotConfigured() {
        AuthService authService = authService();

        URI uri = authService.buildGitHubInstallUri("foo&bar=baz");

        assertThat(uri.toString())
                .isEqualTo("https://github.com/apps/history-tracker/installations/new?state=foo%26bar%3Dbaz");
    }

    @Test
    @DisplayName("installation URL 설정 시 설정된 URL 사용")
    void buildGitHubInstallUriUsesConfiguredInstallationUrlWhenPresent() {
        GitHubAppProperties properties = new GitHubAppProperties(
                "app-id",
                "history-tracker",
                "",
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "https://github.com/apps/custom-app/installations/new",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user",
                "https://api.github.com/user/installations",
                "https://api.github.com/app/installations/{installation_id}/access_tokens",
                "https://api.github.com/installation/repositories",
                "https://api.github.com/repos/{owner}/{repo}/branches"
        );

        URI uri = authService(properties).buildGitHubInstallUri(null);

        assertThat(uri.toString()).isEqualTo("https://github.com/apps/custom-app/installations/new");
    }

    @Test
    @DisplayName("installation URL·app slug 모두 미설정 시 예외 발생")
    void buildGitHubInstallUriThrowsWhenInstallationUrlAndAppSlugAreBlank() {
        GitHubAppProperties properties = new GitHubAppProperties(
                "app-id",
                "",
                "",
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user",
                "https://api.github.com/user/installations",
                "https://api.github.com/app/installations/{installation_id}/access_tokens",
                "https://api.github.com/installation/repositories",
                "https://api.github.com/repos/{owner}/{repo}/branches"
        );

        assertThrows(IllegalStateException.class, () -> authService(properties).buildGitHubInstallUri(null));
    }

    @Test
    @DisplayName("GitHub 로그인 시 토큰 응답 발급")
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

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        verify(gitHubOAuthClient).fetchUser("github-user-token");
    }

    @Test
    @DisplayName("유효하지 않은 GitHub 코드로 로그인 거부")
    void loginWithGitHubRejectsInvalidGitHubCode() {
        AuthService authService = authService();
        when(gitHubOAuthClient.exchangeCode("bad-code"))
                .thenReturn(new GitHubAccessTokenResponse(null, null, null));

        assertThrows(UnauthorizedException.class,
                () -> authService.loginWithGitHub(new GitHubCallbackRequest("bad-code", null)));

        verify(gitHubOAuthClient, never()).fetchUser(any());
    }

    @Test
    @DisplayName("로그인 시 자신의 installation만 동기화하고 타인 것은 무시")
    void loginWithGitHubSyncsOwnInstallationButSkipsOtherAccounts() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        // App manager 권한으로 /user/installations에 함께 노출되는 다른 사용자의 설치 — 동기화 대상이 아님
        GitHubInstallationResponse otherUsersInstallation = new GitHubInstallationResponse(
                11111L,
                new GitHubInstallationAccountResponse("teammate", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation, otherUsersInstallation)));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
        verify(gitHubInstallationService, never()).upsertInstallation(user, otherUsersInstallation);
    }

    @Test
    @DisplayName("토큰 갱신 시 갱신 토큰 교체 후 액세스 토큰 발급")
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
    @DisplayName("로그아웃 시 갱신 토큰 폐기")
    void logoutRevokesRefreshToken() {
        AuthService authService = authService();

        authService.logout(new RefreshTokenRequest("refresh-token"));

        verify(refreshTokenService).revokeRefreshToken("refresh-token");
    }

    private AuthService authService() {
        return authService(GITHUB_PROPERTIES);
    }

    private AuthService authService(GitHubAppProperties gitHubAppProperties) {
        return new AuthService(
                gitHubAppProperties,
                gitHubOAuthClient,
                gitHubInstallationService,
                userService,
                refreshTokenService,
                jwtTokenService,
                JWT_PROPERTIES
        );
    }
}
