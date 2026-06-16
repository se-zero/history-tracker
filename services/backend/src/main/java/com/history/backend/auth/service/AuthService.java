package com.history.backend.auth.service;

import java.net.URI;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.RefreshTokenRequest;
import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.GitHubOAuthClient;
import com.history.backend.security.JwtProperties;
import com.history.backend.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final GitHubAppProperties gitHubAppProperties;
    private final GitHubOAuthClient gitHubOAuthClient;
    private final GitHubInstallationService gitHubInstallationService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    // GitHub OAuth 인증 URL 생성.
    // 이미 앱이 설치된 사용자도, 미설치 사용자도 동일한 OAuth authorize URL로 보낸다.
    // GitHub App의 OAuth authorize 페이지는 동의와 함께 "Install on" 옵션도 함께 제공하므로
    // 신규 사용자는 같은 흐름에서 앱 설치까지 끝낼 수 있다.
    public URI buildGitHubAuthorizeUri(String state) {
        if (gitHubAppProperties.clientId() == null || gitHubAppProperties.clientId().isBlank()) {
            throw new IllegalStateException("GitHub App client_id is not configured.");
        }
        if (gitHubAppProperties.redirectUri() == null || gitHubAppProperties.redirectUri().isBlank()) {
            throw new IllegalStateException("GitHub App redirect_uri is not configured.");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(gitHubAppProperties.authorizeUrl())
                .queryParam("client_id", gitHubAppProperties.clientId())
                .queryParam("redirect_uri", gitHubAppProperties.redirectUri());

        if (state != null && !state.isBlank()) {
            builder.queryParam("state", state);
        }

        return builder.encode().build().toUri();
    }

    // GitHub App 설치 페이지 URL 생성.
    // OAuth authorize는 이미 동의한 사용자에게 설치 화면 없이 자동 리다이렉트되므로,
    // 신규 워크스페이스 설치/추가에는 별도의 App installation URL을 사용한다.
    public URI buildGitHubInstallUri(String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(resolveInstallationUrl());

        if (state != null && !state.isBlank()) {
            builder.queryParam("state", state);
        }

        return builder.encode().build().toUri();
    }

    private String resolveInstallationUrl() {
        if (gitHubAppProperties.installationUrl() != null && !gitHubAppProperties.installationUrl().isBlank()) {
            return gitHubAppProperties.installationUrl();
        }
        if (gitHubAppProperties.appSlug() == null || gitHubAppProperties.appSlug().isBlank()) {
            throw new IllegalStateException("GitHub App installation_url or app_slug is not configured.");
        }
        return "https://github.com/apps/" + gitHubAppProperties.appSlug() + "/installations/new";
    }

    @Transactional
    public TokenResponse loginWithGitHub(GitHubCallbackRequest request) {
        // GitHub code를 user access token으로 교환
        String accessToken = requireAccessToken(gitHubOAuthClient.exchangeCode(request.code()));

        // GitHub 사용자 정보 조회
        GitHubUserResponse gitHubUser = gitHubOAuthClient.fetchUser(accessToken);

        // 내부 user 생성 또는 갱신
        User user = userService.upsertGitHubUser(gitHubUser);

        syncInstallations(user, gitHubUser, accessToken);

        // 서비스 access token과 refresh token 발급
        return new TokenResponse(
                jwtTokenService.issueAccessToken(user.getId()),
                refreshTokenService.issueRefreshToken(user),
                "Bearer",
                jwtProperties.accessTokenTtl().toSeconds()
        );
    }

    private String requireAccessToken(GitHubAccessTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new UnauthorizedException("Invalid GitHub authorization code.");
        }
        return response.accessToken();
    }

    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        RefreshTokenIssue issue = refreshTokenService.rotateRefreshToken(request.refreshToken());
        return new TokenResponse(
                jwtTokenService.issueAccessToken(issue.user().getId()),
                issue.refreshToken(),
                "Bearer",
                jwtProperties.accessTokenTtl().toSeconds()
        );
    }

    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revokeRefreshToken(request.refreshToken());
    }

    // 로그인 시점에 GitHub의 설치 목록을 조회해 본인 계정 설치를 동기화.
    // App installation 콜백(Setup URL)에만 의존하면 외부에서 추가/해제된 설치를 놓치므로
    // 로그인마다 GitHub을 진실의 원천으로 다시 동기화한다.
    // /user/installations는 App manager 권한이 있으면 다른 사용자의 설치도 함께 반환하므로,
    // account.login이 본인 GitHub 계정과 일치하는 설치만 동기화 대상으로 둔다.
    private void syncInstallations(User user, GitHubUserResponse gitHubUser, String accessToken) {
        GitHubInstallationsResponse installations = gitHubOAuthClient.fetchInstallations(accessToken);
        if (installations == null || installations.installations() == null) {
            return;
        }

        installations.installations().stream()
                .filter(installation -> gitHubUser.login().equalsIgnoreCase(installation.account().login()))
                .forEach(installation -> gitHubInstallationService.upsertInstallation(user, installation));
    }
}
