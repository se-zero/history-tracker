package com.history.backend.billing.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.billing.dto.BillingSummaryResponse;
import com.history.backend.billing.dto.BillingSummaryResponse.PlanSource;
import com.history.backend.billing.service.BillingAccountService;
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
@DisplayName("BillingController: 결제 API 인증")
class BillingControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingAccountService billingAccountService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("결제 가능 여부는 JWT 없이 200이고 비밀값을 싣지 않는다")
    void availabilityIsPublic() throws Exception {
        when(billingAccountService.checkoutAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/v1/billing/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutAvailable").value(true));
    }

    @Test
    @DisplayName("결제 요약은 액세스 토큰이 없으면 401")
    void summaryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/billing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));

        verifyNoInteractions(billingAccountService);
    }

    @Test
    @DisplayName("인증된 결제 요약은 서비스 결과를 그대로 반환한다")
    void summaryReturnsServicePayload() throws Exception {
        when(billingAccountService.summary(USER_ID)).thenReturn(new BillingSummaryResponse(
                PlanSource.NONE, null, Instant.parse("2026-08-15T00:00:00Z"), false));

        mockMvc.perform(get("/api/v1/me/billing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planSource").value("NONE"))
                .andExpect(jsonPath("$.subscription").value(nullValue()))
                .andExpect(jsonPath("$.lastSubscriptionEndedAt").value("2026-08-15T00:00:00Z"))
                .andExpect(jsonPath("$.checkoutAvailable").value(false));
    }
}
