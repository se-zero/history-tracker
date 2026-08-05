package com.history.backend.jira.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.IntegrationSelectionFlow;
import com.history.backend.integration.service.JiraTokenService;
import com.history.backend.integration.service.SelectionOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Jira는 사이트(cloudId) → 프로젝트 2단 선택이다. 단계 키는 pipeline-worker가 수집할 때 읽는
// external_ref 키와 같아야 하므로 여기서 정한다.
@Service
@RequiredArgsConstructor
public class JiraSelectionFlow implements IntegrationSelectionFlow {

    public static final String CLOUD_ID = "cloud_id";
    public static final String SITE_NAME = "site_name";
    public static final String PROJECT_KEY = "project_key";
    public static final String PROJECT_NAME = "project_name";

    private final JiraOAuthClient jiraOAuthClient;
    private final JiraClient jiraClient;
    private final JiraTokenService jiraTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    @Override
    public List<SelectionStep> steps() {
        return List.of(
                SelectionStep.required(CLOUD_ID, SITE_NAME, "사이트"),
                SelectionStep.required(PROJECT_KEY, PROJECT_NAME, "프로젝트")
        );
    }

    @Override
    public List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected) {
        // 저장된 연동의 access token을 쓴다 — 필요하면 JiraTokenService가 갱신한다
        String accessToken = jiraTokenService.getAccessToken(projectId);
        return switch (stepKey) {
            case CLOUD_ID -> jiraOAuthClient.listAccessibleResources(accessToken).stream()
                    .map(site -> new SelectionOption(site.cloudId(), site.name()))
                    .toList();
            case PROJECT_KEY -> jiraClient.listProjects(requiredSelection(selected, CLOUD_ID), accessToken).stream()
                    .map(project -> new SelectionOption(project.key(), project.name()))
                    .toList();
            default -> throw new BadRequestException("Unknown Jira selection step: " + stepKey);
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
