package com.history.backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.auth.service.AuthService;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
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
    @DisplayName("GitHub 콜백 처리 → 토큰 응답 반환")
    void callbackReturnsTokenResponse() throws Exception {
        when(authService.loginWithGitHub(any()))
                .thenReturn(new TokenResponse("access-token", "refresh-token", "Bearer", 900));

        mockMvc.perform(get("/api/v1/auth/github/callback")
                        .queryParam("code", "code-123")
                        .queryParam("state", "state-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    @DisplayName("토큰 갱신 → 교체된 토큰 반환")
    void refreshReturnsRotatedTokens() throws Exception {
        when(authService.refresh(any()))
                .thenReturn(new TokenResponse("new-access-token", "new-refresh-token", "Bearer", 900));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"old-refresh-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
    }

    @Test
    @DisplayName("로그아웃 → 204 No Content 반환")
    void logoutReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"refresh-token"}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).logout(any());
    }
}
