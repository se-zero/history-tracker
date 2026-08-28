package com.history.backend.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.service.PlanService;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private PlanService planService;

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
                USER_ID, "github", "12345", "octocat@example.com", "Octocat", null, false, Plan.PAID, null
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

    @Test
    @DisplayName("올바른 업그레이드 코드 → 204 No Content 반환")
    void upgradePlanReturnsNoContentForValidCode() throws Exception {
        mockMvc.perform(post("/api/v1/me/plan/upgrade")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "SECRET-CODE" }
                                """))
                .andExpect(status().isNoContent());

        verify(planService).upgradeToPaid(USER_ID, "SECRET-CODE");
    }

    @Test
    @DisplayName("틀린 업그레이드 코드 → 403 Forbidden 반환")
    void upgradePlanRejectsInvalidCode() throws Exception {
        doThrow(new PlanLimitExceededException("Invalid upgrade code."))
                .when(planService).upgradeToPaid(USER_ID, "WRONG-CODE");

        mockMvc.perform(post("/api/v1/me/plan/upgrade")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "WRONG-CODE" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Invalid upgrade code."));
    }

    @Test
    @DisplayName("액세스 토큰 없으면 401 Unauthorized 반환")
    void upgradePlanRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/me/plan/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "SECRET-CODE" }
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(planService);
    }
}
