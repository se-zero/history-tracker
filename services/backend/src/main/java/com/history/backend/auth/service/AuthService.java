package com.history.backend.auth.service;

import java.net.URI;

import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.auth.domain.User;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubUserResponse;
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

        return builder.build(true).toUri();
    }

    // GitHub OAuth 로그인 처리: 액세스 토큰 교환, 사용자 정보 조회, 회원 가입/업데이트, JWT 발급
    @Transactional
    public TokenResponse loginWithGitHub(GitHubCallbackRequest request) {
        // 1. GitHub로부터 액세스 토큰 교환
        GitHubAccessTokenResponse accessToken = gitHubOAuthClient.exchangeCode(request.code());

        // 2. 액세스 토큰으로 GitHub 사용자 정보 조회
        GitHubUserResponse gitHubUser = gitHubOAuthClient.fetchUser(accessToken.accessToken());
        
        // 3. 사용자 정보로 회원 가입 또는 기존 회원 정보 업데이트
        User user = userService.upsertGitHubUser(gitHubUser);

        // 4. JWT 액세스 토큰과 리프레시 토큰 발급
        return new TokenResponse(
                jwtTokenService.issueAccessToken(user.getId()),
                refreshTokenService.issueRefreshToken(user),
                "Bearer",
                jwtProperties.accessTokenTtl().toSeconds()
        );
    }
}
