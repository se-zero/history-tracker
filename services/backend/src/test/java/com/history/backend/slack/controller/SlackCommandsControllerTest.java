package com.history.backend.slack.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.security.JwtTokenService;
import com.history.backend.slack.service.SlackCommandAck;
import com.history.backend.slack.service.SlackCommandsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SlackCommandsController: Slack slash command HTTP 계층")
class SlackCommandsControllerTest {

    private static final String TIMESTAMP = "1756430400";
    private static final String SIGNATURE = "v0=test-signature";
    private static final String BODY =
            "team_id=T123&user_id=U456&text=hello&response_url=https://hooks.slack.com/commands/x&command=/why-code";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlackCommandsService slackCommandsService;

    // JwtTokenService mock — 이 엔드포인트는 JWT를 쓰지 않지만 Spring 컨텍스트 기동에 필요하다
    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("Authorization 없이 form-urlencoded 커맨드 → 200, ephemeral JSON (Security가 막으면 401이 나온다)")
    void handleCommandWithoutAuthorizationReturns200EphemeralJson() throws Exception {
        when(slackCommandsService.handle(TIMESTAMP, SIGNATURE, BODY))
                .thenReturn(new SlackCommandAck("ephemeral", "질문을 찾고 있어요. 잠시만 기다려 주세요."));

        mockMvc.perform(post("/api/v1/slack/commands")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_type").value("ephemeral"))
                .andExpect(jsonPath("$.text").value("질문을 찾고 있어요. 잠시만 기다려 주세요."));

        verify(slackCommandsService).handle(TIMESTAMP, SIGNATURE, BODY);
    }

    @Test
    @DisplayName("서비스가 UnauthorizedException을 던지면 → 401, GlobalExceptionHandler 형식 (refresh 쿠키 삭제 없음)")
    void handleReturns401WhenServiceThrowsUnauthorizedException() throws Exception {
        when(slackCommandsService.handle(any(), any(), any()))
                .thenThrow(new UnauthorizedException("Invalid Slack request signature."));

        mockMvc.perform(post("/api/v1/slack/commands")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", "v0=invalid")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Slack request signature."));
    }

    @Test
    @DisplayName("Authorization 헤더 없이도 Security가 통과시킨다 — SecurityConfig.permitAll 회귀 방지")
    void handleIsAccessibleWithoutAuthorizationHeader() throws Exception {
        // 이 테스트가 실패하면 SecurityConfig에 /api/v1/slack/commands 의 permitAll이 빠진 것이다.
        // 실패 시 응답: {"message":"Authentication is required."}
        when(slackCommandsService.handle(any(), any(), any()))
                .thenReturn(new SlackCommandAck("ephemeral", "질문을 찾고 있어요. 잠시만 기다려 주세요."));

        mockMvc.perform(post("/api/v1/slack/commands")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_type").value("ephemeral"));
    }
}
