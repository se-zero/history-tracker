package com.history.backend.integration.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.linear.service.LinearSelectionFlow;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("IntegrationResponse: 연동 행에 보여줄 표시 이름")
class IntegrationResponseTest {

    @Test
    @DisplayName("확정된 Linear 연동은 external_ref의 team_name을 표시한다")
    void confirmedLinearIntegrationDisplaysTeamName() {
        Integration integration = Integration.oauth(
                project(),
                IntegrationProvider.LINEAR,
                Map.of(LinearSelectionFlow.TEAM_ID, "team-1", LinearSelectionFlow.TEAM_NAME, "Engineering"),
                new byte[] {1});

        IntegrationResponse response = IntegrationResponse.from(integration);

        assertThat(response.displayName()).isEqualTo("Engineering");
    }

    @Test
    @DisplayName("미확정(pending) Linear 연동은 team을 아직 몰라 고정 문구 \"Linear\"로 폴백한다")
    void pendingLinearIntegrationFallsBackToProviderDisplayName() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.LINEAR, new byte[] {1});

        IntegrationResponse response = IntegrationResponse.from(integration);

        assertThat(response.displayName()).isEqualTo("Linear");
    }

    @Test
    @DisplayName("확정된 Asana 연동은 external_ref의 workspace_name / project_name을 표시한다")
    void confirmedAsanaIntegrationDisplaysWorkspaceAndProjectNames() {
        Integration integration = Integration.oauth(
                project(),
                IntegrationProvider.ASANA,
                Map.of("workspace_name", "Acme Inc", "project_name", "Website Redesign"),
                new byte[] {1});

        IntegrationResponse response = IntegrationResponse.from(integration);

        assertThat(response.displayName()).isEqualTo("Acme Inc / Website Redesign");
    }

    @Test
    @DisplayName("미확정(pending) Asana 연동은 workspace/project를 아직 몰라 고정 문구 \"Asana\"로 폴백한다")
    void pendingAsanaIntegrationFallsBackToProviderDisplayName() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.ASANA, new byte[] {1});

        IntegrationResponse response = IntegrationResponse.from(integration);

        assertThat(response.displayName()).isEqualTo("Asana");
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", UUID.randomUUID());
        return project;
    }
}
