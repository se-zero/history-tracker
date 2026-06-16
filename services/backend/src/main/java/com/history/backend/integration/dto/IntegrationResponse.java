package com.history.backend.integration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;

public record IntegrationResponse(
        UUID id,
        UUID projectId,
        String provider,
        String displayName,
        Map<String, Object> metadata,
        UUID installationId,
        Instant createdAt,
        Instant updatedAt,
        // 마지막으로 새 데이터를 수집한 시각 (checkpoint 갱신 시각). 수집 이력이 없으면 null.
        Instant lastSyncedAt
) {

    public static IntegrationResponse from(Integration integration) {
        return from(integration, null);
    }

    public static IntegrationResponse from(Integration integration, Instant lastSyncedAt) {
        return new IntegrationResponse(
                integration.getId(),
                integration.getProject().getId(),
                integration.getProvider().value(),
                displayName(integration),
                integration.getExternalRef(),
                integration.getInstallation() == null ? null : integration.getInstallation().getId(),
                integration.getCreatedAt(),
                integration.getUpdatedAt(),
                lastSyncedAt
        );
    }

    private static String displayName(Integration integration) {
        IntegrationProvider provider = integration.getProvider();
        return switch (provider) {
            case GITHUB -> integration.getGitHubRepositoryFullName();
            case SLACK -> integration.getSlackWorkspaceName();
            case JIRA -> {
                String projectName = integration.getJiraProjectName();
                yield projectName == null ? integration.getJiraProjectKey() : projectName;
            }
        };
    }
}
