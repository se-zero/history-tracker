package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.RefreshTokenRequest;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.domain.GitHubInstallation;
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
import org.mockito.ArgumentCaptor;
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
            "https://api.github.com/repos/{owner}/{repo}/branches",
            "https://api.github.com/user/installations/{installation_id}/repositories"
    );

    private static final JwtProperties JWT_PROPERTIES = new JwtProperties(
            "jwt-secret",
            Duration.ofMinutes(15),
            Duration.ofDays(14)
    );

    private static final UUID OWN_INSTALLATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_INSTALLATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
                "https://api.github.com/installation/repositories",
                "https://api.github.com/repos/{owner}/{repo}/branches",
                "https://api.github.com/user/installations/{installation_id}/repositories"
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
                "https://api.github.com/repos/{owner}/{repo}/branches",
                "https://api.github.com/user/installations/{installation_id}/repositories"
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
    @DisplayName("로그인 시 본인 개인 installation은 접근 검증 없이 동기화")
    void loginWithGitHubSyncsOwnPersonalInstallationWithoutAccessCheck() {
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

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token"))
                .thenReturn(new GitHubInstallationsResponse(List.of(ownInstallation)));
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
        // 불필요한 API 호출 회귀 방지 — 본인 개인 계정 설치는 접근 검증이 필요 없다
        verify(gitHubOAuthClient, never()).checkInstallationAccess(any(), any());
    }

    @Test
    @DisplayName("로그인 시 접근 가능한(ACCESSIBLE) 조직 installation은 동기화된다")
    void loginWithGitHubSyncsOrganizationInstallationWhenAccessible() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        verify(gitHubInstallationService).upsertInstallation(user, orgInstallation);
    }

    @Test
    @DisplayName("로그인 시 DENIED로 판정된 조직 installation은 동기화하지 않지만 prune은 정상 수행된다")
    void loginWithGitHubSkipsDeniedOrganizationInstallationButStillPrunesMemberships() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        verify(gitHubInstallationService, never()).upsertInstallation(user, orgInstallation);
        // DENIED는 진짜 접근 없음으로 확정됐으니 prune은 그대로 수행돼 멤버십에서 제거된다
        verify(gitHubInstallationService).pruneMemberships(eq(userId), any());
    }

    @Test
    @DisplayName("로그인 시 타인의 개인 installation은 접근 검증을 거쳐 DENIED면 건너뜀")
    void loginWithGitHubSkipsOtherUsersPersonalInstallationWhenDenied() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        verify(gitHubOAuthClient).checkInstallationAccess("github-user-token", 11111L);
        verify(gitHubInstallationService, never()).upsertInstallation(user, otherUsersInstallation);
    }

    @Test
    @DisplayName("UNKNOWN이 하나라도 있으면 prune을 건너뛰고, ACCESSIBLE인 다른 installation은 그대로 동기화된다")
    void loginWithGitHubSkipsPruneMembershipsWhenAnyInstallationAccessIsUnknownButStillSyncsAccessibleOnes() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        // 핵심 회귀 대상: UNKNOWN이 섞여 있으면 일부만 확인된 상태로 prune을 돌리면 안 된다
        verify(gitHubInstallationService, never()).pruneMemberships(any(), any());
        // 그와 무관하게 ACCESSIBLE로 확인된 installation의 수집(upsert)은 계속된다
        verify(gitHubInstallationService).upsertInstallation(user, accessibleOrgInstallation);
    }

    @Test
    @DisplayName("접근 검증이 예외를 던지면 UNKNOWN으로 취급해 로그인은 성공하고 prune은 건너뛴다")
    void loginWithGitHubTreatsAccessCheckExceptionAsUnknownAndSkipsPrune() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
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

        var response = authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(gitHubInstallationService, never()).upsertInstallation(user, orgInstallation);
        verify(gitHubInstallationService).upsertInstallation(user, ownInstallation);
        verify(gitHubInstallationService, never()).pruneMemberships(any(), any());
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

    @Test
    @DisplayName("로그인 시 접근 가능한 installation만 남기고 멤버십 정리(prune)를 호출한다")
    void loginWithGitHubPrunesMembershipsToOnlyAccessibleInstallations() {
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        // 접근 가능했던 두 installation만 남고, 접근 불가로 판정된 세 번째는 kept 집합에서 빠진다
        assertThat(keptInstallationIdsCaptor.getValue())
                .containsExactlyInAnyOrder(OWN_INSTALLATION_ID, ORG_INSTALLATION_ID);
    }

    @Test
    @DisplayName("조직 이탈 등으로 접근 불가 판정된 installation은 멤버십 정리 kept 집합에서 제외된다")
    void loginWithGitHubExcludesOrganizationInstallationLostAccessFromKeptSet() {
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).containsExactly(OWN_INSTALLATION_ID);
    }

    @Test
    @DisplayName("접근 가능한 installation이 하나도 없으면 빈 kept 집합으로 멤버십 정리를 호출해 전부 제거한다")
    void loginWithGitHubPrunesAllMembershipsWhenNoInstallationIsAccessible() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
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

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> keptInstallationIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(gitHubInstallationService).pruneMemberships(eq(userId), keptInstallationIdsCaptor.capture());
        assertThat(keptInstallationIdsCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("installation 목록 조회가 null이면 멤버십 정리를 호출하지 않고 기존 멤버십을 보존한다")
    void loginWithGitHubDoesNotPruneMembershipsWhenFetchInstallationsReturnsNull() {
        AuthService authService = authService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        UUID userId = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
        ReflectionTestUtils.setField(user, "id", userId);
        GitHubAccessTokenResponse githubToken = new GitHubAccessTokenResponse("github-user-token", "bearer", "");
        GitHubUserResponse githubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);

        when(gitHubOAuthClient.exchangeCode("code-123")).thenReturn(githubToken);
        when(gitHubOAuthClient.fetchUser("github-user-token")).thenReturn(githubUser);
        when(gitHubOAuthClient.fetchInstallations("github-user-token")).thenReturn(null);
        when(userService.upsertGitHubUser(githubUser)).thenReturn(user);
        when(jwtTokenService.issueAccessToken(userId)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(user)).thenReturn("refresh-token");

        authService.loginWithGitHub(new GitHubCallbackRequest("code-123", null));

        // GitHub 장애로 installation 목록이 비어 오면(null) 멤버십을 건드리지 않아야 한다 — early return 유지 확인
        verifyNoInteractions(gitHubInstallationService);
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
