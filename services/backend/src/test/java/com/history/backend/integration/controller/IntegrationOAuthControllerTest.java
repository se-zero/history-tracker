package com.history.backend.integration.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.integration.domain.IntegrationProvider;
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
@DisplayName("IntegrationOAuthController: OAuth 동의·콜백 HTTP API")
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
        when(integrationOAuthService.buildAuthorizeUrl(USER_ID, PROJECT_ID, IntegrationProvider.SLACK))
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
        when(integrationOAuthService.completeCallback(IntegrationProvider.SLACK, "auth-code", "signed-state", null))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "slack", null, false));

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
    @DisplayName("잘못된 state → 302, Location에 error=invalid_state&provider=slack (projectId 없이 루트로)")
    void callbackSlackRedirectsToRootWithInvalidStateError() throws Exception {
        when(integrationOAuthService.completeCallback(IntegrationProvider.SLACK, "auth-code", "bad-state", null))
                .thenReturn(new OAuthCallbackOutcome(null, "slack", "invalid_state", false));

        mockMvc.perform(get("/api/v1/integrations/slack/callback")
                        .param("code", "auth-code")
                        .param("state", "bad-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=invalid_state&provider=slack"));
    }

    @Test
    @DisplayName("동의 거부(error=access_denied) → 302, Location에 error=access_denied&provider=slack")
    void callbackSlackRedirectsWithAccessDeniedError() throws Exception {
        when(integrationOAuthService.completeCallback(IntegrationProvider.SLACK, null, "signed-state", "access_denied"))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "slack", "access_denied", false));

        mockMvc.perform(get("/api/v1/integrations/slack/callback")
                        .param("state", "signed-state")
                        .param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "/projects/" + PROJECT_ID + "/sources?error=access_denied&provider=slack"
                ));
    }

    @Test
    @DisplayName("Jira authorize 호출 → 200과 authorizeUrl 본문 반환")
    void authorizeJiraReturnsAuthorizeUrl() throws Exception {
        when(integrationOAuthService.buildAuthorizeUrl(USER_ID, PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn("https://auth.atlassian.com/authorize?client_id=cid&state=signed-state");

        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations/jira/authorize", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl")
                        .value("https://auth.atlassian.com/authorize?client_id=cid&state=signed-state"));
    }

    @Test
    @DisplayName("액세스 토큰 없이 Jira authorize 호출 → 401 Unauthorized")
    void authorizeJiraRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations/jira/authorize", PROJECT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    @DisplayName("Jira 콜백 성공(선택 필요) → 302, Location에 ?connected=jira")
    void callbackJiraRedirectsWithConnectedQueryOnSuccess() throws Exception {
        when(integrationOAuthService.completeCallback(IntegrationProvider.JIRA, "auth-code", "signed-state", null))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "jira", null, false));

        mockMvc.perform(get("/api/v1/integrations/jira/callback")
                        .param("code", "auth-code")
                        .param("state", "signed-state"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "/projects/" + PROJECT_ID + "/sources?connected=jira"
                ));
    }

    @Test
    @DisplayName("Jira 콜백 성공(자동 복원 완료) → 302, Location에 ?connected=jira&restored=true")
    void callbackJiraRedirectsWithRestoredQueryWhenAutoConfirmed() throws Exception {
        when(integrationOAuthService.completeCallback(IntegrationProvider.JIRA, "auth-code", "signed-state", null))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "jira", null, true));

        mockMvc.perform(get("/api/v1/integrations/jira/callback")
                        .param("code", "auth-code")
                        .param("state", "signed-state"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "/projects/" + PROJECT_ID + "/sources?connected=jira&restored=true"
                ));
    }

    @Test
    @DisplayName("Jira 잘못된 state → 302, Location에 error=invalid_state&provider=jira (projectId 없이 루트로)")
    void callbackJiraRedirectsToRootWithInvalidStateError() throws Exception {
        when(integrationOAuthService.completeCallback(IntegrationProvider.JIRA, "auth-code", "bad-state", null))
                .thenReturn(new OAuthCallbackOutcome(null, "jira", "invalid_state", false));

        mockMvc.perform(get("/api/v1/integrations/jira/callback")
                        .param("code", "auth-code")
                        .param("state", "bad-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=invalid_state&provider=jira"));
    }

    @Test
    @DisplayName("Jira 동의 거부(error=access_denied) → 302, Location에 error=access_denied&provider=jira")
    void callbackJiraRedirectsWithAccessDeniedError() throws Exception {
        when(integrationOAuthService.completeCallback(IntegrationProvider.JIRA, null, "signed-state", "access_denied"))
                .thenReturn(new OAuthCallbackOutcome(PROJECT_ID, "jira", "access_denied", false));

        mockMvc.perform(get("/api/v1/integrations/jira/callback")
                        .param("state", "signed-state")
                        .param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "/projects/" + PROJECT_ID + "/sources?error=access_denied&provider=jira"
                ));
    }

    @Test
    @DisplayName("OAuth 동의 흐름이 없는 provider(github) authorize → 404")
    void authorizeRejectsProviderWithoutOAuthFlow() throws Exception {
        // {provider} 범용 라우트가 생겼어도 OAuth로 붙지 않는 provider는 예전처럼 404여야 한다
        when(integrationOAuthService.buildAuthorizeUrl(USER_ID, PROJECT_ID, IntegrationProvider.GITHUB))
                .thenThrow(new NotFoundException("GitHub does not support the OAuth connect flow."));

        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations/github/authorize", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("알 수 없는 provider authorize → 404")
    void authorizeRejectsUnknownProvider() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations/linear/authorize", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNotFound());
    }
}
