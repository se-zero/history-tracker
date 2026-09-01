package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationAccountResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import com.history.backend.github.service.GitHubAppClient;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.GitHubOAuthClient;
import com.history.backend.github.service.GitHubUserTokenService;
import com.history.backend.security.JwtProperties;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
            "https://api.github.com/repos/{owner}/{repo}/branches",
            "https://api.github.com/user/installations/{installation_id}/repositories",
            "https://api.github.com/users/{username}/installation",
            "https://api.github.test/applications/{client_id}/grant",
            "https://api.github.test/app/installations/{installation_id}",
            "https://api.github.test/orgs/{org}/memberships/{username}",
            Duration.ofMinutes(5)
    );

    private static final JwtProperties JWT_PROPERTIES = new JwtProperties(
            "jwt-secret",
            Duration.ofMinutes(15),
            Duration.ofDays(14)
    );

    private static final UUID OWN_INSTALLATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_INSTALLATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MISSING_ORG_INSTALLATION_ROW_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MISSING_USER_INSTALLATION_ROW_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID LISTED_ORG_INSTALLATION_ROW_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Mock
    private GitHubOAuthClient gitHubOAuthClient;

    @Mock
    private GitHubAppClient gitHubAppClient;

    @Mock
    private GitHubInstallationService gitHubInstallationService;

    @Mock
    private GitHubUserTokenService gitHubUserTokenService;

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
                .contains("state=foo%26bar%3Dbaz")
                // 로그인 시 계정 선택 화면 강제 — 세션 남은 계정으로 자동 로그인되지 않도록
                .contains("prompt=select_account");
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
                "https://api.github.com/repos/{owner}/{repo}/branches",
                "https://api.github.com/user/installations/{installation_id}/repositories",
                "https://api.github.com/users/{username}/installation",
                "https://api.github.test/applications/{client_id}/grant",
                "https://api.github.test/app/installations/{installation_id}",
                "https://api.github.test/orgs/{org}/memberships/{username}",
                Duration.ofMinutes(5)
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
                "https://api.github.com/repos/{owner}/{repo}/branches",
                "https://api.github.com/user/installations/{installation_id}/repositories",
                "https://api.github.com/users/{username}/installation",
                "https://api.github.test/applications/{client_id}/grant",
                "https://api.github.test/app/installations/{installation_id}",
                "https://api.github.test/orgs/{org}/memberships/{username}",
                Duration.ofMinutes(5)
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
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.expiresIn()).isEqualTo(900);
        verify(gitHubOAuthClient).fetchUser("github-user-token");
        InOrder inOrder = inOrder(userService, gitHubUserTokenService);
        inOrder.verify(userService).upsertGitHubUser(githubUser);
        inOrder.verify(gitHubUserTokenService).save(eq(userId), eq(githubToken));
    }

    @Test
    @DisplayName("유효하지 않은 GitHub 코드로 로그인 거부")
    void loginWithGitHubRejectsInvalidGitHubCode() {
        AuthService authService = authService();
        when(gitHubOAuthClient.exchangeCode("bad-code"))
                .thenReturn(new GitHubAccessTokenResponse(null, null, null, null, null, null));

        UnauthorizedException thrown = assertThrows(UnauthorizedException.class,
                () -> authService.loginWithGitHub(new GitHubCallbackRequest("bad-code", null, null)));

        // 로그인 실패는 새 세션을 못 만든다는 뜻일 뿐, 브라우저가 이미 가진 다른 유효한
        // refresh 쿠키(재설치 흐름 중일 수 있다)를 지울 이유가 없다.
        assertThat(thrown.clearsRefreshCookie()).isFalse();
        verify(gitHubOAuthClient, never()).fetchUser(any());
        verify(gitHubUserTokenService, never()).save(any(), any());
    }

    @Test
    @DisplayName("로그인 시 본인 개인 installation은 접근 검증 없이 동기화")
    void loginWithGitHubSyncsOwnPersonalInstallationWithoutAccessCheck() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
        // 불필요한 API 호출 회귀 방지 — 본인 개인 계정 설치는 접근 검증이 필요 없다
        verify(gitHubOAuthClient, never()).checkInstallationAccess(any(), any());
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("로그인 시 접근 가능한(ACCESSIBLE) 조직 installation은 동기화된다")
    void loginWithGitHubSyncsOrganizationInstallationWhenAccessible() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse orgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(orgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.ACCESSIBLE);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubInstallationService).upsertInstallation(user, orgInstallation);
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("로그인 시 DENIED로 판정된 조직 installation은 동기화하지 않지만 prune은 정상 수행된다")
    void loginWithGitHubSkipsDeniedOrganizationInstallationButStillPrunesMemberships() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse orgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(orgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.DENIED);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubInstallationService, never()).upsertInstallation(user, orgInstallation);
        // DENIED는 진짜 접근 없음으로 확정됐으니 prune은 그대로 수행돼 멤버십에서 제거된다
        verify(gitHubInstallationService).pruneMemberships(eq(userId), any());
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("로그인 시 타인의 개인 installation은 접근 검증을 거쳐 DENIED면 건너뜀")
    void loginWithGitHubSkipsOtherUsersPersonalInstallationWhenDenied() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        // App manager 권한으로 /user/installations에 함께 노출되는 다른 사용자의 개인 설치
        GitHubInstallationResponse otherUsersInstallation = new GitHubInstallationResponse(
                11111L,
                new GitHubInstallationAccountResponse("teammate", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(otherUsersInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 11111L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.DENIED);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubOAuthClient).checkInstallationAccess("github-user-token", 11111L);
        verify(gitHubInstallationService, never()).upsertInstallation(user, otherUsersInstallation);
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("UNKNOWN이 하나라도 있으면 prune을 건너뛰고, ACCESSIBLE인 다른 installation은 그대로 동기화된다")
    void loginWithGitHubSkipsPruneMembershipsWhenAnyInstallationAccessIsUnknownButStillSyncsAccessibleOnes() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        // GitHub 부분 장애(5xx·타임아웃 등)로 접근 여부를 확인하지 못한 조직 installation
        GitHubInstallationResponse unknownOrgInstallation = new GitHubInstallationResponse(
                33333L,
                new GitHubInstallationAccountResponse("flaky-org", "Organization")
        );
        // 같은 로그인에서 접근이 확인된 다른 조직 installation — 수집은 계속돼야 한다
        GitHubInstallationResponse accessibleOrgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(
                        List.of(unknownOrgInstallation, accessibleOrgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 33333L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.UNKNOWN);
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.ACCESSIBLE);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        // 핵심 회귀 대상: UNKNOWN이 섞여 있으면 일부만 확인된 상태로 prune을 돌리면 안 된다
        verify(gitHubInstallationService, never()).pruneMemberships(any(), any());
        // 그와 무관하게 ACCESSIBLE로 확인된 installation의 수집(upsert)은 계속된다
        verify(gitHubInstallationService).upsertInstallation(user, accessibleOrgInstallation);
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("접근 검증이 예외를 던지면 UNKNOWN으로 취급해 로그인은 성공하고 prune은 건너뛴다")
    void loginWithGitHubTreatsAccessCheckExceptionAsUnknownAndSkipsPrune() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse orgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(orgInstallation, ownInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenThrow(new RuntimeException("GitHub API unavailable"));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).upsertInstallation(user, orgInstallation);
        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
        verify(gitHubInstallationService, never()).pruneMemberships(any(), any());
        verifyGitHubUserTokenSaved(userId, githubToken);
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

        var response = authService.refresh("old-refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.expiresIn()).isEqualTo(900);
    }

    @Test
    @DisplayName("로그아웃 시 갱신 토큰 폐기")
    void logoutRevokesRefreshToken() {
        AuthService authService = authService();

        authService.logout("refresh-token");

        verify(refreshTokenService).revokeRefreshToken("refresh-token");
    }

    @Test
    @DisplayName("refresh 쿠키가 비어 있으면 갱신 거부")
    void refreshRejectsBlankToken() {
        AuthService authService = authService();

        UnauthorizedException thrown = assertThrows(UnauthorizedException.class, () -> authService.refresh(null));
        assertThrows(UnauthorizedException.class, () -> authService.refresh(" "));
        assertThat(thrown.clearsRefreshCookie()).isTrue();
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("로그아웃 시 쿠키가 없으면 폐기를 호출하지 않는다")
    void logoutIgnoresBlankToken() {
        AuthService authService = authService();

        authService.logout(null);
        authService.logout(" ");

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("로그인 시 접근 가능한 installation만 남기고 멤버십 정리(prune)를 호출한다")
    void loginWithGitHubPrunesMembershipsToOnlyAccessibleInstallations() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        GitHubInstallationResponse orgAccessibleInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );
        GitHubInstallationResponse orgInaccessibleInstallation = new GitHubInstallationResponse(
                33333L,
                new GitHubInstallationAccountResponse("lost-org", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(
                        List.of(ownInstallation, orgAccessibleInstallation, orgInaccessibleInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.ACCESSIBLE);
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 33333L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.DENIED);
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.upsertInstallation(user, orgAccessibleInstallation))
                .thenReturn(installation(ORG_INSTALLATION_ID, 22222L, "Organization", "acme-corp", user));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        // 접근 가능했던 두 installation만 남고, 접근 불가로 판정된 세 번째는 kept 집합에서 빠진다
        assertThat(keptInstallationIdsCaptor.getValue())
                .containsExactlyInAnyOrder(OWN_INSTALLATION_ID, ORG_INSTALLATION_ID);
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("조직 이탈 등으로 접근 불가 판정된 installation은 멤버십 정리 kept 집합에서 제외된다")
    void loginWithGitHubExcludesOrganizationInstallationLostAccessFromKeptSet() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        // 예전엔 접근 가능했지만 이번 로그인 시점엔 조직에서 나가 접근이 거부된 installation
        GitHubInstallationResponse lostOrgInstallation = new GitHubInstallationResponse(
                33333L,
                new GitHubInstallationAccountResponse("lost-org", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation, lostOrgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 33333L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.DENIED);
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).containsExactly(OWN_INSTALLATION_ID);
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("접근 가능한 installation이 하나도 없으면 빈 kept 집합으로 멤버십 정리를 호출해 전부 제거한다")
    void loginWithGitHubPrunesAllMembershipsWhenNoInstallationIsAccessible() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse orgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(orgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.DENIED);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).isEmpty();
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("installation 목록 조회가 null이면 멤버십 정리를 호출하지 않고 기존 멤버십을 보존한다")
    void loginWithGitHubDoesNotPruneMembershipsWhenFetchInstallationsReturnsNull() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token")).thenReturn(null);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        // GitHub 장애로 installation 목록이 비어 오면(null) 멤버십을 건드리지 않아야 한다 — early return 유지 확인
        verifyNoInteractions(gitHubInstallationService);
        verifyGitHubUserTokenSaved(userId, githubToken);
    }

    @Test
    @DisplayName("설치 목록이 빈 배열이고 폴백이 본인 개인 설치를 찾으면 upsert되고 prune의 kept 집합에 포함된다")
    void loginWithGitHubFallsBackToUserInstallationWhenListIsEmptyAndSyncsIt() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchUserInstallation("octocat")).thenReturn(Optional.of(ownInstallation));
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        // 폴백으로 등록된 본인 개인 설치가 kept 집합에서 빠지면 곧바로 prune에 지워진다
        assertThat(keptInstallationIdsCaptor.getValue()).containsExactly(OWN_INSTALLATION_ID);
    }

    @Test
    @DisplayName("목록에 조직 설치만 있고 본인 개인 설치가 없으면 조직 설치 동기화와 함께 폴백이 호출된다")
    void loginWithGitHubFallsBackToUserInstallationWhenOnlyOrganizationInstallationsArePresent() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse orgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(orgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.ACCESSIBLE);
        when(gitHubAppClient.fetchUserInstallation("octocat")).thenReturn(Optional.of(ownInstallation));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubAppClient).fetchUserInstallation("octocat");
        verify(gitHubInstallationService).upsertInstallation(user, orgInstallation);
        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
    }

    @Test
    @DisplayName("목록에 본인 개인 설치가 이미 있으면 폴백을 호출하지 않는다")
    void loginWithGitHubSkipsUserInstallationFallbackWhenOwnPersonalInstallationAlreadyListed() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        // 불필요한 API 호출 회귀 방지 — 목록에 이미 본인 개인 설치가 있으면 폴백 조회가 필요 없다
        verify(gitHubAppClient, never()).fetchUserInstallation(any());
    }

    @Test
    @DisplayName("폴백이 미설치(Optional.empty)를 반환하면 prune은 빈 kept 집합으로 정상 진행된다")
    void loginWithGitHubProceedsToPruneWithEmptyKeptSetWhenUserInstallationFallbackFindsNothing() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchUserInstallation("octocat")).thenReturn(Optional.empty());
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubInstallationService, never()).upsertInstallation(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("폴백이 예외를 던지면 prune을 건너뛰고 로그인은 성공한다")
    void loginWithGitHubSkipsPruneAndStillSucceedsWhenUserInstallationFallbackThrows() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchUserInstallation("octocat"))
                .thenThrow(new RuntimeException("GitHub API unavailable"));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        // 폴백 실패로 접근 확인이 불완전하므로 prune은 건너뛰어야 한다 — 그렇지 않으면 멀쩡한 멤버십이 지워진다
        verify(gitHubInstallationService, never()).pruneMemberships(any(), any());
    }

    @Test
    @DisplayName("폴백이 돌려준 설치가 본인 개인 설치가 아니면 등록하지 않고 prune은 정상 진행된다")
    void loginWithGitHubIgnoresUserInstallationFallbackResultWhenNotOwnPersonalInstallation() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        // 방어적 케이스: /users/{username}/installation이 login은 같지만 개인(User) 설치가 아닌 응답을 돌려준 경우
        GitHubInstallationResponse notOwnInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchUserInstallation("octocat")).thenReturn(Optional.of(notOwnInstallation));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubInstallationService, never()).upsertInstallation(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("콜백의 installation_id로 조회된 Organization 설치는 로그인 동기화(prune) 이후에 등록된다")
    void loginWithGitHubRegistersCallbackOrganizationInstallationAfterPruningMemberships() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        // prune이 실제로 돌게 하는 목적으로만 목록에 둔다 — 콜백 경로 검증과는 무관
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        GitHubInstallationResponse callbackOrgInstallation = new GitHubInstallationResponse(
                55555L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(gitHubAppClient.fetchInstallation(55555L)).thenReturn(Optional.of(callbackOrgInstallation));
        when(gitHubOAuthClient.isActiveOrganizationMember("github-user-token", "acme-corp", "octocat"))
                .thenReturn(true);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "55555"));

        // 같은 로그인의 prune이 방금 콜백 경로로 등록한 설치를 지우면 안 된다
        InOrder inOrder = inOrder(gitHubInstallationService);
        inOrder.verify(gitHubInstallationService).pruneMemberships(eq(userId), any());
        inOrder.verify(gitHubInstallationService).upsertInstallation(user, callbackOrgInstallation);
    }

    @Test
    @DisplayName("콜백의 installation_id가 Organization 설치를 가리켜도 멤버십이 활성이 아니면 등록하지 않고 로그인은 성공한다")
    void loginWithGitHubSkipsCallbackOrganizationInstallationWhenMembershipIsNotActive() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse callbackOrgInstallation = new GitHubInstallationResponse(
                55555L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchInstallation(55555L)).thenReturn(Optional.of(callbackOrgInstallation));
        when(gitHubOAuthClient.isActiveOrganizationMember("github-user-token", "acme-corp", "octocat"))
                .thenReturn(false);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "55555"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).upsertInstallation(user, callbackOrgInstallation);
    }

    @Test
    @DisplayName("콜백의 조직 멤버십 확인이 예외를 던지면 등록하지 않고 로그인은 성공한다")
    void loginWithGitHubSkipsCallbackOrganizationInstallationWhenMembershipCheckThrows() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse callbackOrgInstallation = new GitHubInstallationResponse(
                55555L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchInstallation(55555L)).thenReturn(Optional.of(callbackOrgInstallation));
        when(gitHubOAuthClient.isActiveOrganizationMember("github-user-token", "acme-corp", "octocat"))
                .thenThrow(new RuntimeException("GitHub API unavailable"));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "55555"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).upsertInstallation(user, callbackOrgInstallation);
    }

    @Test
    @DisplayName("콜백의 installation_id로 조회된 본인 개인 설치는 등록된다(폴백과 중복돼도 안전)")
    void loginWithGitHubRegistersCallbackOwnPersonalInstallation() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse callbackOwnInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchInstallation(98765L)).thenReturn(Optional.of(callbackOwnInstallation));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "98765"));

        verify(gitHubInstallationService).upsertInstallation(user, callbackOwnInstallation);
        // 본인 개인(User) 설치 경로는 멤버십 확인을 타지 않는다
        verify(gitHubOAuthClient, never()).isActiveOrganizationMember(any(), any(), any());
    }

    @Test
    @DisplayName("콜백의 installation_id가 타인의 개인 설치를 가리키면 등록하지 않는다")
    void loginWithGitHubIgnoresCallbackInstallationOwnedByAnotherUser() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse othersInstallation = new GitHubInstallationResponse(
                77777L,
                new GitHubInstallationAccountResponse("teammate", "User")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchInstallation(77777L)).thenReturn(Optional.of(othersInstallation));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "77777"));

        verify(gitHubInstallationService, never()).upsertInstallation(user, othersInstallation);
    }

    @Test
    @DisplayName("콜백의 installation_id가 조회되지 않으면(404·위조 id) 등록 없이 로그인은 정상 완료된다")
    void loginWithGitHubIgnoresCallbackInstallationWhenNotFound() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchInstallation(99999L)).thenReturn(Optional.empty());
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "99999"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).upsertInstallation(any(), any());
    }

    @Test
    @DisplayName("콜백의 installation 단건 조회가 예외를 던지면 무시하고 로그인은 정상 완료된다")
    void loginWithGitHubIgnoresCallbackInstallationLookupFailureAndStillSucceeds() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(gitHubAppClient.fetchInstallation(12345L))
                .thenThrow(new RuntimeException("GitHub API unavailable"));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "12345"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).upsertInstallation(any(), any());
    }

    @Test
    @DisplayName("콜백에 installation_id가 없으면 installation 단건 조회를 호출하지 않는다")
    void loginWithGitHubSkipsCallbackInstallationLookupWhenInstallationIdIsNull() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubAppClient, never()).fetchInstallation(any());
    }

    @Test
    @DisplayName("콜백의 installation_id가 숫자가 아니면 installation 단건 조회를 호출하지 않고 로그인은 정상 완료된다")
    void loginWithGitHubSkipsCallbackInstallationLookupWhenInstallationIdIsNotNumeric() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of()));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, "abc"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubAppClient, never()).fetchInstallation(any());
    }

    @Test
    @DisplayName("목록에 없는 조직 멤버십은 사용자 토큰의 활성 멤버십 확인을 거쳐 kept 집합에 남는다")
    void loginWithGitHubKeepsUnverifiableOrganizationMembershipWhenUserIsActiveMember() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        // /user/installations 목록에는 없지만(저장소 0개) DB엔 이미 저장돼 있는 조직 멤버십.
        // DB엔 개명 전 login이 남아 있고, GitHub 원격 응답은 개명된 login을 준다 — 판정은 원격 기준이어야 한다.
        GitHubInstallation missingOrgMembership =
                installation(MISSING_ORG_INSTALLATION_ROW_ID, 77777L, "Organization", "empty-org-old", user);
        GitHubInstallationResponse missingOrgInstallation = new GitHubInstallationResponse(
                77777L,
                new GitHubInstallationAccountResponse("empty-org-renamed", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.findMemberInstallations(userId))
                .thenReturn(List.of(missingOrgMembership));
        when(gitHubAppClient.fetchInstallation(77777L)).thenReturn(Optional.of(missingOrgInstallation));
        when(gitHubOAuthClient.isActiveOrganizationMember("github-user-token", "empty-org-renamed", "octocat"))
                .thenReturn(true);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        // 멤버십 확인 인자는 DB 행의 login이 아니라 fetchInstallation 원격 응답의 account().login()이어야 한다
        verify(gitHubOAuthClient).isActiveOrganizationMember("github-user-token", "empty-org-renamed", "octocat");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue())
                .containsExactlyInAnyOrder(OWN_INSTALLATION_ID, MISSING_ORG_INSTALLATION_ROW_ID);
    }

    @Test
    @DisplayName("목록에 없는 조직 멤버십이 활성 멤버가 아니면(이탈) kept 집합에서 제외되고 정리(prune)된다")
    void loginWithGitHubExcludesUnverifiableOrganizationMembershipWhenMembershipIsNotActive() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        GitHubInstallation missingOrgMembership =
                installation(MISSING_ORG_INSTALLATION_ROW_ID, 77777L, "Organization", "empty-org", user);
        GitHubInstallationResponse missingOrgInstallation = new GitHubInstallationResponse(
                77777L,
                new GitHubInstallationAccountResponse("empty-org", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.findMemberInstallations(userId))
                .thenReturn(List.of(missingOrgMembership));
        when(gitHubAppClient.fetchInstallation(77777L)).thenReturn(Optional.of(missingOrgInstallation));
        when(gitHubOAuthClient.isActiveOrganizationMember("github-user-token", "empty-org", "octocat"))
                .thenReturn(false);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).containsExactly(OWN_INSTALLATION_ID);
    }

    @Test
    @DisplayName("앱이 삭제되어 조직 설치 단건 조회가 비어있으면 kept에서 제외되고 멤버십 확인은 호출하지 않는다")
    void loginWithGitHubExcludesUnverifiableOrganizationMembershipWhenInstallationWasDeleted() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        GitHubInstallation missingOrgMembership =
                installation(MISSING_ORG_INSTALLATION_ROW_ID, 77777L, "Organization", "empty-org", user);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.findMemberInstallations(userId))
                .thenReturn(List.of(missingOrgMembership));
        when(gitHubAppClient.fetchInstallation(77777L)).thenReturn(Optional.empty());
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubOAuthClient, never()).isActiveOrganizationMember(any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).containsExactly(OWN_INSTALLATION_ID);
    }

    @Test
    @DisplayName("유지 판정의 멤버십 확인이 예외를 던지면 prune 전체를 건너뛰고 로그인은 정상 완료된다")
    void loginWithGitHubSkipsPruneEntirelyWhenMembershipCheckThrows() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        GitHubInstallation missingOrgMembership =
                installation(MISSING_ORG_INSTALLATION_ROW_ID, 77777L, "Organization", "empty-org", user);
        GitHubInstallationResponse missingOrgInstallation = new GitHubInstallationResponse(
                77777L,
                new GitHubInstallationAccountResponse("empty-org", "Organization")
        );

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.findMemberInstallations(userId))
                .thenReturn(List.of(missingOrgMembership));
        when(gitHubAppClient.fetchInstallation(77777L)).thenReturn(Optional.of(missingOrgInstallation));
        when(gitHubOAuthClient.isActiveOrganizationMember("github-user-token", "empty-org", "octocat"))
                .thenThrow(new RuntimeException("GitHub API unavailable"));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).pruneMemberships(any(), any());
    }

    @Test
    @DisplayName("부재 멤버십이 User 타입이면 유지 판정을 수행하지 않는다")
    void loginWithGitHubSkipsKeepDeterminationForUserTypeMissingMembership() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        // 목록에 본인 개인 설치가 이미 있어 폴백은 돌지 않는 상태
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        // 목록에는 없지만 DB엔 있는, User 타입(개인) 멤버십 — Organization이 아니므로 유지 판정 대상이 아니다
        GitHubInstallation missingUserMembership =
                installation(MISSING_USER_INSTALLATION_ROW_ID, 88888L, "User", "someone-else", user);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.findMemberInstallations(userId))
                .thenReturn(List.of(missingUserMembership));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubAppClient, never()).fetchInstallation(88888L);
    }

    @Test
    @DisplayName("조직 설치가 API 목록에 있으면(DENIED 포함) 유지 판정 없이 기존 정리 결과를 따른다")
    void loginWithGitHubSkipsKeepDeterminationWhenOrganizationInstallationIsListedByApi() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = githubAccessTokenResponse();
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        GitHubInstallationResponse ownInstallation = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("octocat", "User")
        );
        GitHubInstallationResponse deniedOrgInstallation = new GitHubInstallationResponse(
                22222L,
                new GitHubInstallationAccountResponse("acme-corp", "Organization")
        );
        // DB에는 있고, /user/installations 목록에도 있는(DENIED로 판정된) 조직 멤버십
        GitHubInstallation listedOrgMembership =
                installation(LISTED_ORG_INSTALLATION_ROW_ID, 22222L, "Organization", "acme-corp", user);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation, deniedOrgInstallation)));
        when(gitHubOAuthClient.checkInstallationAccess("github-user-token", 22222L))
                .thenReturn(GitHubOAuthClient.InstallationAccess.DENIED);
        when(gitHubInstallationService.upsertInstallation(user, ownInstallation))
                .thenReturn(installation(OWN_INSTALLATION_ID, 98765L, "User", "octocat", user));
        when(gitHubInstallationService.findMemberInstallations(userId))
                .thenReturn(List.of(listedOrgMembership));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null, null));

        verify(gitHubAppClient, never()).fetchInstallation(22222L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        // 기존 DENIED 정리 결과 그대로 — 목록에 있었으니 유지 판정 없이 kept에서 빠진다
        assertThat(keptInstallationIdsCaptor.getValue()).containsExactly(OWN_INSTALLATION_ID);
    }

    private AuthService authService() {
        return authService(GITHUB_PROPERTIES);
    }

    private AuthService authService(GitHubAppProperties gitHubAppProperties) {
        return new AuthService(
                gitHubAppProperties,
                gitHubOAuthClient,
                gitHubAppClient,
                gitHubInstallationService,
                gitHubUserTokenService,
                userService,
                refreshTokenService,
                jwtTokenService,
                JWT_PROPERTIES
        );
    }

    private GitHubAccessTokenResponse githubAccessTokenResponse() {
        return new GitHubAccessTokenResponse(
                "github-user-token",
                "bearer",
                "",
                "ghr_test",
                28800L,
                15897600L
        );
    }

    private void verifyGitHubUserTokenSaved(UUID userId, GitHubAccessTokenResponse githubToken) {
        verify(gitHubUserTokenService).save(eq(userId), eq(githubToken));
    }

    private GitHubInstallation installation(
            UUID id,
            long installationId,
            String accountType,
            String accountLogin,
            User installer
    ) {
        GitHubInstallation installation = new GitHubInstallation(installationId, accountType, accountLogin, installer);
        ReflectionTestUtils.setField(installation, "id", id);
        return installation;
    }
}
