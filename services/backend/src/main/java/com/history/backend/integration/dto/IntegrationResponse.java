package com.history.backend.integration.dto;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.jira.service.JiraSelectionFlow;
import com.history.backend.slack.service.SlackOAuthConnectFlow;

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

    /**
     * 연동 행에 보여줄 이름. provider마다 무엇이 "연결된 대상"인지 다르므로 여기서 정한다.
     *
     * <p>exhaustive switch라 새 provider를 추가하면 컴파일이 깨진다 — 표시 이름을 정하지 않은 채
     * 화면에 빈 이름이 나가는 것을 막는 의도된 가드다.</p>
     */
    private static String displayName(Integration integration) {
        IntegrationProvider provider = integration.getProvider();
        return switch (provider) {
            case GITHUB -> integration.getGitHubRepositoryFullName();
            case SLACK -> integration.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_NAME);
            // 사이트 / 프로젝트 — 여러 Atlassian 사이트를 쓰는 조직에서는 프로젝트 이름만으로
            // 어느 사이트의 것인지 알 수 없다. pending 행은 프로젝트가 아직 없어 사이트만 남는다.
            case JIRA -> joinNonBlank(
                    integration.externalRefValue(JiraSelectionFlow.SITE_NAME),
                    integration.isPendingSelection() ? null : jiraProjectLabel(integration));
        };
    }

    // 프로젝트 이름이 없으면 키로 대체한다 (이름을 못 받아온 연동도 무엇인지는 보이게)
    private static String jiraProjectLabel(Integration integration) {
        String projectName = integration.externalRefValue(JiraSelectionFlow.PROJECT_NAME);
        return projectName == null ? integration.externalRefValue(JiraSelectionFlow.PROJECT_KEY) : projectName;
    }

    // null·공백을 걸러 " / "로 잇는다. 남는 게 없으면 null — 프론트가 폴백 문구를 쓴다.
    private static String joinNonBlank(String... parts) {
        String joined = Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" / "));
        return joined.isEmpty() ? null : joined;
    }
}
