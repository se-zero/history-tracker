package com.history.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Spring Security: 인증·검증 오류 응답 형식")
class SecurityErrorResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("액세스 토큰 없으면 401 표준 오류 응답 반환")
    void rejectMissingAccessTokenWithErrorResponse() throws Exception {
        mockMvc.perform(post("/api/v1/projects/f4dfc513-bb7b-41f4-aaf9-46bcc18380f8/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Why did this file change?"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    @DisplayName("빈 GitHub 콜백 코드 → 400 표준 오류 응답 반환")
    void rejectBlankGitHubCallbackCodeWithErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/github/callback")
                        .queryParam("code", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Request validation failed."));
    }
}
