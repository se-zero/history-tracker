package com.history.backend.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.ForbiddenException;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.graph.dto.GraphActivityResponse;
import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphConstellationResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.dto.GraphSearchResponse;
import com.history.backend.graph.dto.GraphSubgraphResponse;
import com.history.backend.graph.dto.SubgraphRequest;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GraphService: 그래프 조회 서비스")
class GraphServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private ProjectService projectService;

    @Mock
    private AiEngineGraphClient aiEngineGraphClient;

    @InjectMocks
    private GraphService graphService;

    @Test
    @DisplayName("소유권 확인 후 그래프 조회")
    void fetchesGraphAfterOwnershipCheck() {
        GraphResponse expected = new GraphResponse(List.of(), List.of());
        when(aiEngineGraphClient.fetchOverview(PROJECT_ID, 50, "commit")).thenReturn(expected);

        GraphResponse result = graphService.getProjectGraph(USER_ID, PROJECT_ID, 50, "commit");

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchOverview(PROJECT_ID, 50, "commit");
    }

    @Test
    @DisplayName("소유권 검증 실패 시 ai-engine 호출 차단")
    void doesNotCallAiEngineWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.getProjectGraph(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForbiddenException.class);

        // 인가 실패 시 ai-engine에 절대 요청이 가면 안 된다 (데이터 누출 차단)
        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 노드 검색 (질의는 trim해 전달)")
    void searchesNodesAfterOwnershipCheck() {
        GraphSearchResponse expected = GraphSearchResponse.empty();
        when(aiEngineGraphClient.searchNodes(PROJECT_ID, "auth", 20)).thenReturn(expected);

        GraphSearchResponse result = graphService.searchNodes(USER_ID, PROJECT_ID, " auth ", 20);

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).searchNodes(PROJECT_ID, "auth", 20);
    }

    @Test
    @DisplayName("노드 검색 — 빈 질의는 ai-engine 왕복 없이 빈 결과")
    void returnsEmptySearchWithoutAiEngineCallForBlankQuery() {
        GraphSearchResponse result = graphService.searchNodes(USER_ID, PROJECT_ID, "  ", null);

        assertThat(result.nodes()).isEmpty();
        // 빈 질의여도 인가 게이트는 통과해야 한다
        verify(projectService).getProject(USER_ID, PROJECT_ID);
        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("노드 검색 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotSearchWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.searchNodes(USER_ID, PROJECT_ID, "auth", null))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 evidence 서브그래프 조회")
    void fetchesSubgraphAfterOwnershipCheck() {
        SubgraphRequest request = new SubgraphRequest(List.of(new EvidenceRef("commit", "abc1234")));
        GraphSubgraphResponse expected = GraphSubgraphResponse.empty();
        when(aiEngineGraphClient.fetchSubgraph(PROJECT_ID, request.evidence())).thenReturn(expected);

        GraphSubgraphResponse result = graphService.getSubgraph(USER_ID, PROJECT_ID, request);

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchSubgraph(PROJECT_ID, request.evidence());
    }

    @Test
    @DisplayName("서브그래프 조회 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotFetchSubgraphWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        SubgraphRequest request = new SubgraphRequest(List.of(new EvidenceRef("commit", "abc1234")));
        assertThatThrownBy(() -> graphService.getSubgraph(USER_ID, PROJECT_ID, request))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 빌드 트리거 (projectId가 실제 빌드 인자)")
    void triggersBuildAfterOwnershipCheck() {
        GraphBuildStatusResponse expected =
                new GraphBuildStatusResponse("running", true, "2026-06-24T00:00:00+00:00", null, null);
        when(aiEngineGraphClient.triggerBuild(PROJECT_ID, true)).thenReturn(expected);

        GraphBuildStatusResponse result = graphService.buildProjectGraph(USER_ID, PROJECT_ID, true);

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 빌드 트리거보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).triggerBuild(PROJECT_ID, true);
    }

    @Test
    @DisplayName("빌드 트리거 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotTriggerBuildWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.buildProjectGraph(USER_ID, PROJECT_ID, false))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 빌드 상태 조회")
    void fetchesBuildStatusAfterOwnershipCheck() {
        GraphBuildStatusResponse expected =
                new GraphBuildStatusResponse("idle", null, null, null, null);
        when(aiEngineGraphClient.fetchBuildStatus(PROJECT_ID)).thenReturn(expected);

        GraphBuildStatusResponse result = graphService.getBuildStatus(USER_ID, PROJECT_ID);

        assertThat(result).isSameAs(expected);
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchBuildStatus(PROJECT_ID);
    }

    @Test
    @DisplayName("빌드 상태 조회 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotFetchStatusWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.getBuildStatus(USER_ID, PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 그래프 활동 상태 조회")
    void fetchesGraphActivityAfterOwnershipCheck() {
        GraphActivityResponse expected = new GraphActivityResponse("collecting");
        when(aiEngineGraphClient.fetchGraphActivity(PROJECT_ID)).thenReturn(expected);

        GraphActivityResponse result = graphService.getGraphActivity(USER_ID, PROJECT_ID);

        assertThat(result).isSameAs(expected);
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchGraphActivity(PROJECT_ID);
    }

    @Test
    @DisplayName("그래프 활동 상태 조회 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotFetchActivityWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.getGraphActivity(USER_ID, PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 성좌 뷰 조회")
    void fetchesConstellationAfterOwnershipCheck() {
        GraphConstellationResponse expected = GraphConstellationResponse.empty();
        when(aiEngineGraphClient.fetchConstellation(PROJECT_ID, 400)).thenReturn(expected);

        GraphConstellationResponse result = graphService.getConstellation(USER_ID, PROJECT_ID, 400);

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchConstellation(PROJECT_ID, 400);
    }

    @Test
    @DisplayName("성좌 뷰 조회 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotFetchConstellationWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.getConstellation(USER_ID, PROJECT_ID, null))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("소유권 확인 후 작업 단위 이웃 조회 (nodeId는 trim해 전달)")
    void fetchesWorkUnitAfterOwnershipCheck() {
        GraphResponse expected = GraphResponse.empty();
        when(aiEngineGraphClient.fetchWorkUnit(PROJECT_ID, "4:abc:12")).thenReturn(expected);

        GraphResponse result = graphService.getWorkUnit(USER_ID, PROJECT_ID, " 4:abc:12 ");

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchWorkUnit(PROJECT_ID, "4:abc:12");
    }

    @Test
    @DisplayName("작업 단위 이웃 조회 — 빈 nodeId는 ai-engine 왕복 없이 빈 결과")
    void returnsEmptyWorkUnitWithoutAiEngineCallForBlankNodeId() {
        GraphResponse result = graphService.getWorkUnit(USER_ID, PROJECT_ID, "  ");

        assertThat(result.nodes()).isEmpty();
        // 빈 nodeId여도 인가 게이트는 통과해야 한다
        verify(projectService).getProject(USER_ID, PROJECT_ID);
        verifyNoInteractions(aiEngineGraphClient);
    }

    @Test
    @DisplayName("작업 단위 이웃 조회 — 소유권 검증 실패 시 ai-engine 미호출")
    void doesNotFetchWorkUnitWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.getWorkUnit(USER_ID, PROJECT_ID, "4:abc:12"))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(aiEngineGraphClient);
    }
}
