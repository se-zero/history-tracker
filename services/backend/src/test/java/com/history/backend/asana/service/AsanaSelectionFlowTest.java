package com.history.backend.asana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.AsanaTokenService;
import com.history.backend.integration.service.SelectionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Asana는 워크스페이스 → 프로젝트 2단 선택이다(JiraSelectionFlow와 동일 구조). key·labelKey는
// 그대로 external_ref 키가 되어 pipeline-worker가 수집할 때 읽는다 — 오타는 수집이 조용히
// 실패하는 원인이 된다.
@DisplayName("AsanaSelectionFlow: Asana 워크스페이스·프로젝트 선택 단계")
class AsanaSelectionFlowTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    private final AsanaApiClient asanaApiClient = mock(AsanaApiClient.class);
    private final AsanaTokenService asanaTokenService = mock(AsanaTokenService.class);
    private final AsanaSelectionFlow flow = new AsanaSelectionFlow(asanaApiClient, asanaTokenService);

    @Test
    void providerIsAsana() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.ASANA);
    }

    @Test
    @DisplayName("단계 선언은 워크스페이스 → 프로젝트 2단 — key=workspace_gid/project_gid, labelKey=workspace_name/project_name")
    void stepsDeclaresWorkspaceThenProjectStep() {
        assertThat(flow.steps()).containsExactly(
                SelectionStep.required(AsanaSelectionFlow.WORKSPACE_GID, AsanaSelectionFlow.WORKSPACE_NAME, "워크스페이스"),
                SelectionStep.required(AsanaSelectionFlow.PROJECT_GID, AsanaSelectionFlow.PROJECT_NAME, "프로젝트")
        );
        assertThat(AsanaSelectionFlow.WORKSPACE_GID).isEqualTo("workspace_gid");
        assertThat(AsanaSelectionFlow.WORKSPACE_NAME).isEqualTo("workspace_name");
        assertThat(AsanaSelectionFlow.PROJECT_GID).isEqualTo("project_gid");
        assertThat(AsanaSelectionFlow.PROJECT_NAME).isEqualTo("project_name");
    }

    @Test
    @DisplayName("워크스페이스 단계 후보 조회 — 저장된 연동의 access token으로 AsanaApiClient 워크스페이스 목록을 후보로 변환")
    void optionsForWorkspaceStepListsAsanaWorkspaces() {
        when(asanaTokenService.getAccessToken(PROJECT_ID)).thenReturn("asana-access-token");
        when(asanaApiClient.listWorkspaces("asana-access-token")).thenReturn(List.of(
                new AsanaApiClient.AsanaWorkspace("ws-1", "Acme"),
                new AsanaApiClient.AsanaWorkspace("ws-2", "Beta Corp")
        ));

        List<SelectionOption> result = flow.options(PROJECT_ID, AsanaSelectionFlow.WORKSPACE_GID, Map.of());

        assertThat(result).containsExactly(
                new SelectionOption("ws-1", "Acme"),
                new SelectionOption("ws-2", "Beta Corp")
        );
    }

    @Test
    @DisplayName("프로젝트 단계 후보 조회 — 앞 단계에서 고른 workspace_gid로 AsanaApiClient 프로젝트 목록을 후보로 변환")
    void optionsForProjectStepListsAsanaProjectsForSelectedWorkspace() {
        when(asanaTokenService.getAccessToken(PROJECT_ID)).thenReturn("asana-access-token");
        when(asanaApiClient.listProjects("ws-1", "asana-access-token")).thenReturn(List.of(
                new AsanaApiClient.AsanaProject("proj-1", "Roadmap"),
                new AsanaApiClient.AsanaProject("proj-2", "Backlog")
        ));

        List<SelectionOption> result = flow.options(
                PROJECT_ID, AsanaSelectionFlow.PROJECT_GID, Map.of(AsanaSelectionFlow.WORKSPACE_GID, "ws-1"));

        assertThat(result).containsExactly(
                new SelectionOption("proj-1", "Roadmap"),
                new SelectionOption("proj-2", "Backlog")
        );
    }

    @Test
    @DisplayName("워크스페이스 미선택 상태로 프로젝트 단계 후보 조회 → BadRequestException 발생")
    void optionsForProjectStepWithoutWorkspaceSelectionThrowsBadRequest() {
        assertThatThrownBy(() -> flow.options(PROJECT_ID, AsanaSelectionFlow.PROJECT_GID, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("선언되지 않은 단계 키 조회 → BadRequestException 발생")
    void optionsRejectsUnknownStepKey() {
        assertThatThrownBy(() -> flow.options(PROJECT_ID, "unknown_step", Map.of()))
                .isInstanceOf(BadRequestException.class);
    }
}
