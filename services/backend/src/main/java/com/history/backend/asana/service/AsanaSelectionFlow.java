package com.history.backend.asana.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.AsanaTokenService;
import com.history.backend.integration.service.IntegrationSelectionFlow;
import com.history.backend.integration.service.SelectionOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Asana는 워크스페이스(workspace) → 프로젝트 2단 선택이다(JiraSelectionFlow와 동일 구조).
// 단계 키는 pipeline-worker가 수집할 때 읽는 external_ref 키와 같아야 하므로 여기서 정한다.
@Service
@RequiredArgsConstructor
public class AsanaSelectionFlow implements IntegrationSelectionFlow {

    public static final String WORKSPACE_GID = "workspace_gid";
    public static final String WORKSPACE_NAME = "workspace_name";
    public static final String PROJECT_GID = "project_gid";
    public static final String PROJECT_NAME = "project_name";

    private final AsanaApiClient asanaApiClient;
    private final AsanaTokenService asanaTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.ASANA;
    }

    @Override
    public List<SelectionStep> steps() {
        return List.of(
                SelectionStep.required(WORKSPACE_GID, WORKSPACE_NAME, "워크스페이스"),
                SelectionStep.required(PROJECT_GID, PROJECT_NAME, "프로젝트")
        );
    }

    @Override
    public List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected) {
        // 저장된 연동의 access token을 쓴다 — 필요하면 AsanaTokenService가 갱신한다
        String accessToken = asanaTokenService.getAccessToken(projectId);
        return switch (stepKey) {
            case WORKSPACE_GID -> asanaApiClient.listWorkspaces(accessToken).stream()
                    .map(workspace -> new SelectionOption(workspace.gid(), workspace.name()))
                    .toList();
            case PROJECT_GID -> asanaApiClient.listProjects(requiredSelection(selected, WORKSPACE_GID), accessToken).stream()
                    .map(project -> new SelectionOption(project.gid(), project.name()))
                    .toList();
            default -> throw new BadRequestException("Unknown Asana selection step: " + stepKey);
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
