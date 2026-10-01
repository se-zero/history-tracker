package com.history.backend.billing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.history.backend.billing.service.PaddleWebhookService;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.security.JwtTokenService;
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
@DisplayName("PaddleWebhookController: Paddle 결제 웹훅 HTTP 계층")
class PaddleWebhookControllerTest {

    private static final String SIGNATURE_HEADER = "ts=1758682800;h1=deadbeef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaddleWebhookService paddleWebhookService;

    // JwtTokenService mock — 이 엔드포인트는 JWT를 쓰지 않지만 Spring 컨텍스트 기동에 필요하다
    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("Authorization 헤더 없이도 200 (permitAll) — Security가 막으면 401이 나온다")
    void handleWebhookWithoutAuthorizationReturns200() throws Exception {
        String body = """
                {"event_id":"evt_01","event_type":"subscription.created"}""";

        mockMvc.perform(post("/api/v1/billing/webhook/paddle")
                        .header("Paddle-Signature", SIGNATURE_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(paddleWebhookService).handle(SIGNATURE_HEADER, body);
    }

    @Test
    @DisplayName("서비스가 UnauthorizedException을 던지면 → 401")
    void handleWebhookReturns401WhenServiceThrowsUnauthorizedException() throws Exception {
        doThrow(new UnauthorizedException("Invalid Paddle webhook signature."))
                .when(paddleWebhookService).handle(any(), any());

        mockMvc.perform(post("/api/v1/billing/webhook/paddle")
                        .header("Paddle-Signature", "ts=1;h1=invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Paddle webhook signature."));
    }

    @Test
    @DisplayName("Paddle-Signature 헤더와 원본 본문이 그대로 서비스에 전달된다")
    void handleWebhookPassesHeaderAndRawBodyThrough() throws Exception {
        String body = """
                {"event_id":"evt_02","event_type":"subscription.updated","data":{}}""";

        mockMvc.perform(post("/api/v1/billing/webhook/paddle")
                        .header("Paddle-Signature", SIGNATURE_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(paddleWebhookService).handle(SIGNATURE_HEADER, body);
    }
}
