package com.history.backend.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Integration: pending 상태 생성·확정·자격증명 교체와 구 status 값 호환")
class IntegrationTest {

    // 중립 값(pending_selection) 이전에 Jira가 쓰던 status 값
    private static final String LEGACY_PENDING_PROJECT = "pending_project";

    @Test
    @DisplayName("pendingSelection 생성 시 status=pending_selection 한 줄만 담긴다 — 쓰기는 항상 중립 값이다")
    void pendingSelectionCreatesRowWithNeutralPendingStatusOnly() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});

        assertThat(integration.isPendingSelection()).isTrue();
        assertThat(integration.getExternalRef())
                .containsExactly(Map.entry(Integration.STATUS, Integration.STATUS_PENDING_SELECTION));
        assertThat(integration.getEncryptedCredential()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("applyExternalRef 호출 후 status가 사라지고 사이트·프로젝트 정보로 통째로 교체된다")
    void applyExternalRefReplacesExternalRefAndClearsStatus() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});

        integration.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));

        assertThat(integration.isPendingSelection()).isFalse();
        assertThat(integration.externalRefValue("project_key")).isEqualTo("PROJ");
        assertThat(integration.externalRefValue("project_name")).isEqualTo("Project");
        assertThat(integration.getExternalRef()).doesNotContainKey(Integration.STATUS);
    }

    @Test
    @DisplayName("markPendingSelection 호출 후에도 사이트·프로젝트 정보는 남고 status만 pending으로 바뀐다")
    void markPendingSelectionKeepsSiteAndProjectInfo() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        integration.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));

        integration.markPendingSelection();

        assertThat(integration.isPendingSelection()).isTrue();
        assertThat(integration.externalRefValue("cloud_id")).isEqualTo("cloud-1");
        assertThat(integration.externalRefValue("site_name")).isEqualTo("acme");
        assertThat(integration.externalRefValue("project_key")).isEqualTo("PROJ");
        assertThat(integration.externalRefValue("project_name")).isEqualTo("Project");
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

    @Test
    @DisplayName("구 Jira 전용 status 값(pending_project)도 pending으로 읽는다 — 저장된 행 마이그레이션 없이 넘어가기 위함")
    void legacyPendingProjectStatusIsReadAsPending() {
        Integration integration = legacyPendingSelection(Map.of(Integration.STATUS, LEGACY_PENDING_PROJECT));

        assertThat(integration.isPendingSelection()).isTrue();
    }

    @Test
    @DisplayName("구 status 값으로 저장된 행도 확정하면 status 자체가 사라진다 — 옛 값이 남아 영영 pending으로 읽히지 않는다")
    void applyExternalRefClearsLegacyPendingStatus() {
        Integration integration = legacyPendingSelection(Map.of(
                Integration.STATUS, LEGACY_PENDING_PROJECT,
                "cloud_id", "cloud-1"));

        integration.applyExternalRef(Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ"));

        assertThat(integration.isPendingSelection()).isFalse();
        assertThat(integration.getExternalRef()).doesNotContainKey(Integration.STATUS);
    }

    @Test
    @DisplayName("구 status 값으로 저장된 행을 pending으로 되돌리면 중립 값으로 갱신된다 — 읽기만 호환하고 쓰기는 중립이다")
    void markPendingSelectionRewritesLegacyStatusToNeutralValue() {
        Integration integration = legacyPendingSelection(Map.of(
                Integration.STATUS, LEGACY_PENDING_PROJECT,
                "cloud_id", "cloud-1"));

        integration.markPendingSelection();

        assertThat(integration.isPendingSelection()).isTrue();
        assertThat(integration.getExternalRef())
                .containsEntry(Integration.STATUS, Integration.STATUS_PENDING_SELECTION);
        assertThat(integration.externalRefValue("cloud_id")).isEqualTo("cloud-1");
    }

    // 중립 값 이전 배포가 저장한 행 재현. 상수가 아니라 문자열 리터럴을 쓰는 이유는 DB에 실제로 들어 있는
    // 값이 계약이기 때문이다 — 엔티티 상수를 참조하면 상수를 바꿔도 테스트가 따라가 호환이 깨진 걸 놓친다.
    private Integration legacyPendingSelection(Map<String, Object> externalRef) {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        integration.applyExternalRef(externalRef);
        return integration;
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", UUID.randomUUID());
        return project;
    }
}
