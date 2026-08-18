package com.history.backend.clickup.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.ClickUpTokenService;
import com.history.backend.integration.service.IntegrationSelectionFlow;
import com.history.backend.integration.service.SelectionOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// ClickUp은 워크스페이스 → 스페이스 → 폴더(선택) → 리스트 최대 4단 선택이다. folder는 건너뛸 수 있어
// list 단계는 앞서 folder를 골랐는지에 따라 서로 다른 API를 호출한다(폴더 안 리스트 vs folderless 리스트).
@Service
@RequiredArgsConstructor
public class ClickUpSelectionFlow implements IntegrationSelectionFlow {

    public static final String WORKSPACE_ID = "workspace_id";
    public static final String WORKSPACE_NAME = "workspace_name";
    public static final String SPACE_ID = "space_id";
    public static final String SPACE_NAME = "space_name";
    public static final String FOLDER_ID = "folder_id";
    public static final String FOLDER_NAME = "folder_name";
    public static final String LIST_ID = "list_id";
    public static final String LIST_NAME = "list_name";

    private final ClickUpApiClient clickUpApiClient;
    private final ClickUpTokenService clickUpTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.CLICKUP;
    }

    @Override
    public List<SelectionStep> steps() {
        return List.of(
                SelectionStep.required(WORKSPACE_ID, WORKSPACE_NAME, "워크스페이스"),
                SelectionStep.required(SPACE_ID, SPACE_NAME, "스페이스"),
                SelectionStep.optional(FOLDER_ID, FOLDER_NAME, "폴더"),
                SelectionStep.required(LIST_ID, LIST_NAME, "리스트")
        );
    }

    @Override
    public List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected) {
        // 저장된 연동의 access token을 쓴다 — ClickUp 토큰은 만료가 없어 갱신 없이 그대로 조회한다
        String accessToken = clickUpTokenService.getAccessToken(projectId);
        return switch (stepKey) {
            case WORKSPACE_ID -> clickUpApiClient.listWorkspaces(accessToken).stream()
                    .map(workspace -> new SelectionOption(workspace.id(), workspace.name()))
                    .toList();
            case SPACE_ID -> clickUpApiClient.listSpaces(requiredSelection(selected, WORKSPACE_ID), accessToken).stream()
                    .map(space -> new SelectionOption(space.id(), space.name()))
                    .toList();
            case FOLDER_ID -> clickUpApiClient.listFolders(requiredSelection(selected, SPACE_ID), accessToken).stream()
                    .map(folder -> new SelectionOption(folder.id(), folder.name()))
                    .toList();
            // folder는 건너뛸 수 있으므로 선행 선택에 folder_id가 있는지로 폴더 안 리스트/folderless 리스트를 가른다
            case LIST_ID -> {
                String folderId = selected.get(FOLDER_ID);
                List<ClickUpApiClient.ClickUpList> lists = folderId != null && !folderId.isBlank()
                        ? clickUpApiClient.listFolderLists(folderId, accessToken)
                        : clickUpApiClient.listFolderlessLists(requiredSelection(selected, SPACE_ID), accessToken);
                yield lists.stream()
                        .map(list -> new SelectionOption(list.id(), list.name()))
                        .toList();
            }
            default -> throw new BadRequestException("Unknown ClickUp selection step: " + stepKey);
        };
    }

    private String requiredSelection(Map<String, String> selected, String key) {
        String value = selected.get(key);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Missing prior selection: " + key);
        }
        return value;
    }
}
