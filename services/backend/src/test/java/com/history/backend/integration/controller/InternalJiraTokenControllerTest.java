package com.history.backend.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.history.backend.integration.service.JiraTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("InternalJiraTokenController: 내부 Jira 토큰 API")
class InternalJiraTokenControllerTest {

    @Test
    @DisplayName("Jira 토큰 확보 요청 → 204 No Content 반환")
    void ensureJiraTokenReturnsNoContent() {
        JiraTokenService jiraTokenService = mock(JiraTokenService.class);
        InternalJiraTokenController controller = new InternalJiraTokenController(jiraTokenService);
        UUID projectId = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

        ResponseEntity<Void> response = controller.ensureJiraToken(projectId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(jiraTokenService).ensureAccessToken(projectId);
    }
}
