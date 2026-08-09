package com.history.backend.linear.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.IntegrationSelectionFlow;
import com.history.backend.integration.service.LinearTokenService;
import com.history.backend.integration.service.SelectionOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Linear는 team 1단 선택이다. 단계 키는 pipeline-worker가 수집할 때 읽는
// external_ref 키와 같아야 하므로 여기서 정한다.
@Service
@RequiredArgsConstructor
public class LinearSelectionFlow implements IntegrationSelectionFlow {

    public static final String TEAM_ID = "team_id";
    public static final String TEAM_NAME = "team_name";

    private final LinearApiClient linearApiClient;
    private final LinearTokenService linearTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.LINEAR;
    }

    @Override
    public List<SelectionStep> steps() {
        return List.of(
                SelectionStep.required(TEAM_ID, TEAM_NAME, "팀")
        );
    }

    @Override
    public List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected) {
        if (!TEAM_ID.equals(stepKey)) {
            throw new BadRequestException("Unknown Linear selection step: " + stepKey);
        }
        // 저장된 연동의 access token을 쓴다 — 필요하면 LinearTokenService가 갱신한다
        String accessToken = linearTokenService.getAccessToken(projectId);
        return linearApiClient.listTeams(accessToken).stream()
                .map(team -> new SelectionOption(team.id(), team.name()))
                .toList();
    }
}
