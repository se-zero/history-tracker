package com.history.backend.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.service.UserService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("MeController: 현재 사용자 정보 조회·탈퇴·약관 동의")
class MeControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        // 모든 요청을 USER_ID로 인증 통과시킨다
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("현재 사용자 정보 반환")
    void meReturnsCurrentUser() throws Exception {
        UserResponse response = new UserResponse(
                USER_ID, "github", "12345", "octocat@example.com", "Octocat", null, false
        );
        when(userService.getCurrentUser(USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("octocat@example.com"))
                .andExpect(jsonPath("$.requiresConsent").value(false));
    }

    @Test
    @DisplayName("현재 사용자 탈퇴 처리")
    void deleteMeDeactivatesCurrentUser() throws Exception {
        mockMvc.perform(delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNoContent());

        verify(userService).deactivateUser(USER_ID);
    }

    @Test
    @DisplayName("약관 동의 기록 → 204 No Content 반환")
    void recordConsentReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/me/consent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNoContent());

        verify(userService).recordConsent(USER_ID);
    }

    @Test
    @DisplayName("액세스 토큰 없으면 401 Unauthorized 반환")
    void recordConsentRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/me/consent"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }
}
