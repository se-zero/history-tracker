package com.history.backend.linear.service;

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
import com.history.backend.integration.service.LinearTokenService;
import com.history.backend.integration.service.SelectionOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Linear는 team 1단 선택이다. key·labelKey는 그대로 external_ref 키가 되어 pipeline-worker가
// 수집할 때 읽는다 — 오타는 수집이 조용히 실패하는 원인이 된다.
@DisplayName("LinearSelectionFlow: Linear team 선택 단계")
class LinearSelectionFlowTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    private final LinearApiClient linearApiClient = mock(LinearApiClient.class);
    private final LinearTokenService linearTokenService = mock(LinearTokenService.class);
    private final LinearSelectionFlow flow = new LinearSelectionFlow(linearApiClient, linearTokenService);

    @Test
    void providerIsLinear() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.LINEAR);
    }

    @Test
    @DisplayName("단계 선언은 team 1단 — key=team_id, labelKey=team_name")
    void stepsDeclaresSingleTeamStep() {
        assertThat(flow.steps()).containsExactly(
                SelectionStep.required(LinearSelectionFlow.TEAM_ID, LinearSelectionFlow.TEAM_NAME, "팀")
        );
        assertThat(LinearSelectionFlow.TEAM_ID).isEqualTo("team_id");
        assertThat(LinearSelectionFlow.TEAM_NAME).isEqualTo("team_name");
    }

    @Test
    @DisplayName("team 단계 후보 조회 — 저장된 연동의 access token으로 LinearApiClient 팀 목록을 후보로 변환")
    void optionsForTeamStepListsLinearTeams() {
        when(linearTokenService.getAccessToken(PROJECT_ID)).thenReturn("linear-access-token");
        when(linearApiClient.listTeams("linear-access-token")).thenReturn(List.of(
                new LinearApiClient.LinearTeam("team-1", "Engineering"),
                new LinearApiClient.LinearTeam("team-2", "Design")
        ));

        List<SelectionOption> result = flow.options(PROJECT_ID, LinearSelectionFlow.TEAM_ID, Map.of());

        assertThat(result).containsExactly(
                new SelectionOption("team-1", "Engineering"),
                new SelectionOption("team-2", "Design")
        );
    }

    @Test
    @DisplayName("선언되지 않은 단계 키 조회 → BadRequestException 발생")
    void optionsRejectsUnknownStepKey() {
        assertThatThrownBy(() -> flow.options(PROJECT_ID, "unknown_step", Map.of()))
                .isInstanceOf(BadRequestException.class);
    }
}
