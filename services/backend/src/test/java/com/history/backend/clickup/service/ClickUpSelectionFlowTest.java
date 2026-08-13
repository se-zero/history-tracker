package com.history.backend.clickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.ClickUpTokenService;
import com.history.backend.integration.service.SelectionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ClickUp은 워크스페이스 → 스페이스 → 폴더(선택) → 리스트 최대 4단 선택이다. folder는 건너뛸 수 있어
// list 단계는 앞서 folder를 골랐는지에 따라 서로 다른 API를 호출한다(폴더 안 리스트 vs folderless 리스트).
@DisplayName("ClickUpSelectionFlow: ClickUp 워크스페이스·스페이스·폴더(선택)·리스트 선택 단계")
class ClickUpSelectionFlowTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    private final ClickUpApiClient clickUpApiClient = mock(ClickUpApiClient.class);
    private final ClickUpTokenService clickUpTokenService = mock(ClickUpTokenService.class);
    private final ClickUpSelectionFlow flow = new ClickUpSelectionFlow(clickUpApiClient, clickUpTokenService);

    @Test
    void providerIsClickUp() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.CLICKUP);
    }

    @Test
    @DisplayName("단계 선언은 워크스페이스 → 스페이스 → 폴더(선택) → 리스트 4단이다")
    void stepsDeclaresFourStepsWithOptionalFolder() {
        assertThat(flow.steps()).containsExactly(
                SelectionStep.required(ClickUpSelectionFlow.WORKSPACE_ID, ClickUpSelectionFlow.WORKSPACE_NAME, "워크스페이스"),
                SelectionStep.required(ClickUpSelectionFlow.SPACE_ID, ClickUpSelectionFlow.SPACE_NAME, "스페이스"),
                SelectionStep.optional(ClickUpSelectionFlow.FOLDER_ID, ClickUpSelectionFlow.FOLDER_NAME, "폴더"),
                SelectionStep.required(ClickUpSelectionFlow.LIST_ID, ClickUpSelectionFlow.LIST_NAME, "리스트")
        );
    }

    @Test
    @DisplayName("워크스페이스 단계 후보 조회 — ClickUpApiClient 워크스페이스 목록을 후보로 변환")
    void optionsForWorkspaceStepListsClickUpWorkspaces() {
        when(clickUpTokenService.getAccessToken(PROJECT_ID)).thenReturn("clickup-access-token");
        when(clickUpApiClient.listWorkspaces("clickup-access-token")).thenReturn(List.of(
                new ClickUpApiClient.ClickUpWorkspace("team-1", "Acme"),
                new ClickUpApiClient.ClickUpWorkspace("team-2", "Beta Corp")
        ));

        List<SelectionOption> result = flow.options(PROJECT_ID, ClickUpSelectionFlow.WORKSPACE_ID, Map.of());

        assertThat(result).containsExactly(
                new SelectionOption("team-1", "Acme"),
                new SelectionOption("team-2", "Beta Corp")
        );
    }

    @Test
    @DisplayName("스페이스 단계 후보 조회 — 앞 단계에서 고른 workspace_id로 스페이스 목록을 후보로 변환")
    void optionsForSpaceStepListsClickUpSpacesForSelectedWorkspace() {
        when(clickUpTokenService.getAccessToken(PROJECT_ID)).thenReturn("clickup-access-token");
        when(clickUpApiClient.listSpaces("team-1", "clickup-access-token")).thenReturn(List.of(
                new ClickUpApiClient.ClickUpSpace("space-1", "Engineering")
        ));

        List<SelectionOption> result = flow.options(
                PROJECT_ID, ClickUpSelectionFlow.SPACE_ID, Map.of(ClickUpSelectionFlow.WORKSPACE_ID, "team-1"));

        assertThat(result).containsExactly(new SelectionOption("space-1", "Engineering"));
    }

    @Test
    @DisplayName("폴더 단계 후보 조회 — 앞 단계에서 고른 space_id로 폴더 목록을 후보로 변환")
    void optionsForFolderStepListsClickUpFoldersForSelectedSpace() {
        when(clickUpTokenService.getAccessToken(PROJECT_ID)).thenReturn("clickup-access-token");
        when(clickUpApiClient.listFolders("space-1", "clickup-access-token")).thenReturn(List.of(
                new ClickUpApiClient.ClickUpFolder("folder-1", "Sprint Backlog")
        ));

        List<SelectionOption> result = flow.options(
                PROJECT_ID, ClickUpSelectionFlow.FOLDER_ID, Map.of(ClickUpSelectionFlow.SPACE_ID, "space-1"));

        assertThat(result).containsExactly(new SelectionOption("folder-1", "Sprint Backlog"));
    }

    @Test
    @DisplayName("리스트 단계 후보 조회 — folder_id가 선택돼 있으면 폴더 안 리스트를 조회한다 (folderless는 호출하지 않는다)")
    void optionsForListStepWithFolderSelectedListsFolderLists() {
        when(clickUpTokenService.getAccessToken(PROJECT_ID)).thenReturn("clickup-access-token");
        when(clickUpApiClient.listFolderLists("folder-1", "clickup-access-token")).thenReturn(List.of(
                new ClickUpApiClient.ClickUpList("list-1", "To Do")
        ));

        List<SelectionOption> result = flow.options(PROJECT_ID, ClickUpSelectionFlow.LIST_ID, Map.of(
                ClickUpSelectionFlow.WORKSPACE_ID, "team-1",
                ClickUpSelectionFlow.SPACE_ID, "space-1",
                ClickUpSelectionFlow.FOLDER_ID, "folder-1"
        ));

        assertThat(result).containsExactly(new SelectionOption("list-1", "To Do"));
        verify(clickUpApiClient, never()).listFolderlessLists(anyString(), anyString());
    }

    @Test
    @DisplayName("리스트 단계 후보 조회 — folder_id가 없으면(폴더 단계를 건너뛰면) space_id로 folderless 리스트를 조회한다 (폴더 리스트는 호출하지 않는다)")
    void optionsForListStepWithoutFolderSelectedListsFolderlessLists() {
        when(clickUpTokenService.getAccessToken(PROJECT_ID)).thenReturn("clickup-access-token");
        when(clickUpApiClient.listFolderlessLists("space-1", "clickup-access-token")).thenReturn(List.of(
                new ClickUpApiClient.ClickUpList("list-3", "Backlog")
        ));

        List<SelectionOption> result = flow.options(PROJECT_ID, ClickUpSelectionFlow.LIST_ID, Map.of(
                ClickUpSelectionFlow.WORKSPACE_ID, "team-1",
                ClickUpSelectionFlow.SPACE_ID, "space-1"
        ));

        assertThat(result).containsExactly(new SelectionOption("list-3", "Backlog"));
        verify(clickUpApiClient, never()).listFolderLists(anyString(), anyString());
    }

    @Test
    @DisplayName("워크스페이스 미선택 상태로 스페이스 단계 후보 조회 → BadRequestException 발생")
    void optionsForSpaceStepWithoutWorkspaceSelectionThrowsBadRequest() {
        assertThatThrownBy(() -> flow.options(PROJECT_ID, ClickUpSelectionFlow.SPACE_ID, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("선언되지 않은 단계 키 조회 → BadRequestException 발생")
    void optionsRejectsUnknownStepKey() {
        assertThatThrownBy(() -> flow.options(PROJECT_ID, "unknown_step", Map.of()))
                .isInstanceOf(BadRequestException.class);
    }
}
