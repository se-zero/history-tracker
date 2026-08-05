package com.history.backend.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Integration: Jira pending 상태 생성·확정·자격증명 교체")
class IntegrationTest {

    @Test
    @DisplayName("jiraPending 생성 시 status=pending_project 한 줄만 담긴다")
    void jiraPendingCreatesRowWithPendingStatusOnly() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});

        assertThat(integration.isPendingSelection()).isTrue();
        assertThat(integration.getExternalRef())
                .containsExactly(Map.entry(Integration.STATUS, Integration.STATUS_PENDING_SELECTION));
        assertThat(integration.getEncryptedCredential()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("completeJiraProject 호출 후 status가 사라지고 사이트·프로젝트 정보로 통째로 교체된다")
    void completeJiraProjectReplacesExternalRefAndClearsStatus() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});

        integration.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));

        assertThat(integration.isPendingSelection()).isFalse();
        assertThat(integration.selectionValue("project_key")).isEqualTo("PROJ");
        assertThat(integration.selectionValue("project_name")).isEqualTo("Project");
        assertThat(integration.getExternalRef()).doesNotContainKey(Integration.STATUS);
    }

    @Test
    @DisplayName("markJiraPendingProject 호출 후에도 사이트·프로젝트 정보는 남고 status만 pending으로 바뀐다")
    void markJiraPendingProjectKeepsSiteAndProjectInfo() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        integration.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));

        integration.markPendingSelection();

        assertThat(integration.isPendingSelection()).isTrue();
        assertThat(integration.selectionValue("cloud_id")).isEqualTo("cloud-1");
        assertThat(integration.selectionValue("site_name")).isEqualTo("acme");
        assertThat(integration.selectionValue("project_key")).isEqualTo("PROJ");
        assertThat(integration.selectionValue("project_name")).isEqualTo("Project");
    }

    @Test
    @DisplayName("updateCredential은 새 바이트 배열을 방어적으로 복사해 저장한다")
    void updateCredentialDefensivelyCopiesBytes() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        byte[] newCredential = new byte[] {4, 5, 6};

        integration.updateCredential(newCredential);
        newCredential[0] = 9;

        assertThat(integration.getEncryptedCredential()).containsExactly(4, 5, 6);
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", UUID.randomUUID());
        return project;
    }
}
