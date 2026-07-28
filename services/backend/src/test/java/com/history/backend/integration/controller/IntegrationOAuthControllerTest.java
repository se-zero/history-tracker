package com.history.backend.integration.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.history.backend.integration.service.IntegrationOAuthService;
import com.history.backend.integration.service.OAuthCallbackOutcome;
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
@DisplayName("IntegrationOAuthController: Slack OAuth 동의·콜백 HTTP API")
class IntegrationOAuthControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntegrationOAuthService integrationOAuthService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("authorize 호출 → 200과 authorizeUrl 본문 반환")
    void authorizeSlackReturnsAuthorizeUrl() throws Exception {
        when(integrationOAuthService.buildSlackAuthorizeUrl(USER_ID, PROJECT_ID))
                .thenReturn("https://slack.com/oauth/v2/authorize?client_id=cid&state=signed-state");

        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations/slack/authorize", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl")
                        .value("https://slack.com/oauth/v2/authorize?client_id=cid&state=signed-state"));
    }

    @Test
    @DisplayName("액세스 토큰 없이 authorize 호출 → 401 Unauthorized")
    void authorizeSlackRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations/slack/authorize", PROJECT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    @DisplayName("콜백 성공 → 302, Location에 ?connected=slack")
    void callbackSlackRedirectsWithConnectedQueryOnSuccess() throws Exception {
        when(integrationOAuthService.completeSlackCallback("auth-code", "signed-state", null))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "slack", null));

        mockMvc.perform(get("/api/v1/integrations/slack/callback")
                        .param("code", "auth-code")
                        .param("state", "signed-state"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "/projects/" + PROJECT_ID + "/sources?connected=slack"
                ));
    }

    @Test
    @DisplayName("잘못된 state → 302, Location에 error=invalid_state (projectId 없이 루트로)")
    void callbackSlackRedirectsToRootWithInvalidStateError() throws Exception {
        when(integrationOAuthService.completeSlackCallback("auth-code", "bad-state", null))
                .thenReturn(new OAuthCallbackOutcome(null, "slack", "invalid_state"));

        mockMvc.perform(get("/api/v1/integrations/slack/callback")
                        .param("code", "auth-code")
                        .param("state", "bad-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=invalid_state"));
    }

    @Test
    @DisplayName("동의 거부(error=access_denied) → 302, Location에 error=access_denied")
    void callbackSlackRedirectsWithAccessDeniedError() throws Exception {
        when(integrationOAuthService.completeSlackCallback(null, "signed-state", "access_denied"))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "slack", "access_denied"));

        mockMvc.perform(get("/api/v1/integrations/slack/callback")
                        .param("state", "signed-state")
                        .param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "/projects/" + PROJECT_ID + "/sources?error=access_denied"
                ));
    }
}
