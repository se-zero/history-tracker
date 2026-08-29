package com.history.backend.slack.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.security.JwtTokenService;
import com.history.backend.slack.service.SlackEventAck;
import com.history.backend.slack.service.SlackEventsService;
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
@DisplayName("SlackEventsController: Slack Events API HTTP 계층")
class SlackEventsControllerTest {

    private static final String TIMESTAMP = "1756430400";
    private static final String SIGNATURE = "v0=test-signature";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlackEventsService slackEventsService;

    // JwtTokenService mock — 이 엔드포인트는 JWT를 쓰지 않지만 Spring 컨텍스트 기동에 필요하다
    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("Authorization 없이 url_verification → 200, challenge 반환 (Security가 막으면 401이 나온다)")
    void handleUrlVerificationWithoutAuthorizationReturns200WithChallenge() throws Exception {
        String body = """
                {"token":"unused","challenge":"test-challenge","type":"url_verification"}""";
        when(slackEventsService.handle(TIMESTAMP, SIGNATURE, body))
                .thenReturn(new SlackEventAck(200, "test-challenge"));

        mockMvc.perform(post("/api/v1/slack/events")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("test-challenge"));

        verify(slackEventsService).handle(TIMESTAMP, SIGNATURE, body);
    }

    @Test
    @DisplayName("Authorization 없이 event_callback → 200 (challenge 없음)")
    void handleEventCallbackWithoutAuthorizationReturns200() throws Exception {
        String body = """
                {"type":"event_callback","team_id":"T123","event":{"type":"app_uninstalled"}}""";
        when(slackEventsService.handle(TIMESTAMP, SIGNATURE, body))
                .thenReturn(new SlackEventAck(200, null));

        mockMvc.perform(post("/api/v1/slack/events")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(slackEventsService).handle(TIMESTAMP, SIGNATURE, body);
    }

    @Test
    @DisplayName("서비스가 UnauthorizedException을 던지면 → 401, GlobalExceptionHandler 형식 (refresh 쿠키 삭제 없음)")
    void handleReturns401WhenServiceThrowsUnauthorizedException() throws Exception {
        when(slackEventsService.handle(any(), any(), any()))
                .thenThrow(new UnauthorizedException("Invalid Slack request signature."));

        mockMvc.perform(post("/api/v1/slack/events")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", "v0=invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Slack request signature."));
    }

    @Test
    @DisplayName("Authorization 헤더 없이도 Security가 통과시킨다 — SecurityConfig.permitAll 회귀 방지")
    void handleIsAccessibleWithoutAuthorizationHeader() throws Exception {
        // 이 테스트가 실패하면 SecurityConfig에 /api/v1/slack/events 의 permitAll이 빠진 것이다.
        // 실패 시 응답: {"message":"Authentication is required."}
        when(slackEventsService.handle(any(), any(), any()))
                .thenReturn(new SlackEventAck(200, null));

        mockMvc.perform(post("/api/v1/slack/events")
                        .header("X-Slack-Request-Timestamp", TIMESTAMP)
                        .header("X-Slack-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
