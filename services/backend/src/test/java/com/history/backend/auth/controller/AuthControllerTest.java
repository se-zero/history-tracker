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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Test
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
    void callbackReturnsTokenResponse() throws Exception {
        when(authService.loginWithGitHub(any()))
                .thenReturn(new TokenResponse("access-token", "refresh-token", "Bearer", 900));

        mockMvc.perform(get("/api/v1/auth/github/callback")
                        .queryParam("code", "code-123")
                        .queryParam("state", "state-123")
                        .queryParam("installation_id", "98765"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
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
