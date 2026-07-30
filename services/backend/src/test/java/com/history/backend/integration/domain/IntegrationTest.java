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
        Integration integration = Integration.jiraPending(project(), new byte[] {1, 2, 3});

        assertThat(integration.isJiraPendingProject()).isTrue();
        assertThat(integration.getExternalRef())
                .containsExactly(Map.entry(Integration.JIRA_STATUS, Integration.JIRA_STATUS_PENDING_PROJECT));
        assertThat(integration.getEncryptedCredential()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("completeJiraProject 호출 후 status가 사라지고 사이트·프로젝트 정보로 통째로 교체된다")
    void completeJiraProjectReplacesExternalRefAndClearsStatus() {
        Integration integration = Integration.jiraPending(project(), new byte[] {1, 2, 3});

        integration.completeJiraProject("cloud-1", "acme", "PROJ", "Project");

        assertThat(integration.isJiraPendingProject()).isFalse();
        assertThat(integration.getJiraProjectKey()).isEqualTo("PROJ");
        assertThat(integration.getJiraProjectName()).isEqualTo("Project");
        assertThat(integration.getExternalRef()).doesNotContainKey(Integration.JIRA_STATUS);
    }

    @Test
    @DisplayName("updateCredential은 새 바이트 배열을 방어적으로 복사해 저장한다")
    void updateCredentialDefensivelyCopiesBytes() {
        Integration integration = Integration.jiraPending(project(), new byte[] {1, 2, 3});
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
