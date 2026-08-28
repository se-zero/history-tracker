package com.history.backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;

import com.history.backend.auth.RefreshTokenCookies;
import com.history.backend.auth.service.AuthService;
import com.history.backend.auth.service.IssuedSession;
import com.history.backend.common.error.GlobalExceptionHandler;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.security.JwtTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("AuthController: OAuth 인증 HTTP API")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("GitHub OAuth authorize 요청 → GitHub으로 리다이렉트")
    void authorizeGitHubRedirectsToGitHub() throws Exception {
        when(authService.buildGitHubAuthorizeUri("state-123"))
                .thenReturn(URI.create("https://github.com/apps/history-tracker/installations/new?state=state-123"));

        mockMvc.perform(get("/api/v1/auth/github/authorize")
                        .queryParam("state", "state-123"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "https://github.com/apps/history-tracker/installations/new?state=state-123"
                ));
    }

    @Test
    @DisplayName("GitHub App 설치 요청 → GitHub으로 리다이렉트")
    void installGitHubAppRedirectsToGitHub() throws Exception {
        when(authService.buildGitHubInstallUri("state-123"))
                .thenReturn(URI.create("https://github.com/apps/history-tracker/installations/new?state=state-123"));

        mockMvc.perform(get("/api/v1/auth/github/install")
                        .queryParam("state", "state-123"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "https://github.com/apps/history-tracker/installations/new?state=state-123"
                ));
    }

    @Test
    @DisplayName("GitHub 콜백 → access는 JSON, refresh는 httpOnly 쿠키")
    void callbackReturnsAccessTokenAndSetsRefreshCookie() throws Exception {
        when(authService.loginWithGitHub(any())).thenReturn(session("access-token", "refresh-token"));

        mockMvc.perform(get("/api/v1/auth/github/callback")
                        .queryParam("code", "code-123")
                        .queryParam("state", "state-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().value(RefreshTokenCookies.NAME, "refresh-token"))
                .andExpect(cookie().httpOnly(RefreshTokenCookies.NAME, true))
                .andExpect(cookie().path(RefreshTokenCookies.NAME, RefreshTokenCookies.PATH));
    }

    @Test
    @DisplayName("토큰 갱신 → 쿠키의 refresh를 읽고 새 access JSON + 새 refresh 쿠키")
    void refreshReadsCookieAndRotates() throws Exception {
        when(authService.refresh("old-refresh-token"))
                .thenReturn(session("new-access-token", "new-refresh-token"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookies.NAME, "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().value(RefreshTokenCookies.NAME, "new-refresh-token"));

        verify(authService).refresh("old-refresh-token");
    }

    @Test
    @DisplayName("refresh 쿠키가 없으면 401")
    void refreshWithoutCookieReturnsUnauthorized() throws Exception {
        when(authService.refresh(isNull())).thenThrow(new UnauthorizedException("Invalid refresh token."));

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃 → 204, refresh 쿠키 삭제")
    void logoutClearsRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(RefreshTokenCookies.NAME, "refresh-token")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(RefreshTokenCookies.NAME, 0));

        verify(authService).logout("refresh-token");
    }

    private static IssuedSession session(String accessToken, String refreshToken) {
        return new IssuedSession(accessToken, refreshToken, 900, Duration.ofDays(14));
    }
}
