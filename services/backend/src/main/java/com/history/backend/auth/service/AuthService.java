package com.history.backend.auth.service;

import java.net.URI;
import java.util.Optional;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.RefreshTokenRequest;
import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
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

    // GitHub OAuth 인증 URL 생성
    public URI buildGitHubAuthorizeUri(String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(gitHubAppProperties.authorizeUrl())
                .queryParam("client_id", gitHubAppProperties.clientId())
                .queryParam("redirect_uri", gitHubAppProperties.redirectUri());

        if (state != null && !state.isBlank()) {
            builder.queryParam("state", state);
        }

        return builder.encode().build().toUri();
    }

    @Transactional
    public TokenResponse loginWithGitHub(GitHubCallbackRequest request) {
        // GitHub code를 user access token으로 교환
        String accessToken = requireAccessToken(gitHubOAuthClient.exchangeCode(request.code()));

        // GitHub 사용자 정보 조회
        GitHubUserResponse gitHubUser = gitHubOAuthClient.fetchUser(accessToken);

        // 내부 user 생성 또는 갱신
        User user = userService.upsertGitHubUser(gitHubUser);

        if (request.installationId() != null) {
            // callback installation_id 기준 설치 정보 저장
            findInstallation(accessToken, request.installationId())
                    .ifPresent(installation -> gitHubInstallationService.upsertInstallation(user, installation));
        }

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

    private Optional<GitHubInstallationResponse> findInstallation(String accessToken, Long installationId) {
        GitHubInstallationsResponse installations = gitHubOAuthClient.fetchInstallations(accessToken);
        if (installations == null || installations.installations() == null) {
            return Optional.empty();
        }

        return installations.installations().stream()
                .filter(installation -> installationId.equals(installation.id()))
                .findFirst();
    }
}
