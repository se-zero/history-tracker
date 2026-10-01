package com.history.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.PlanService;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.conversation.service.AiEngineQueryClient;
import com.history.backend.conversation.service.AiEngineQueryResult;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.mcp.service.McpBindingService.Bound;
import com.history.backend.mcp.service.McpBindingService.NoProjects;
import com.history.backend.mcp.service.McpBindingService.Unbound;
import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpQueryService: MCP 도구 4종의 동작 (한도·분당 상한·실패 사유)")
class McpQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("266acdfb-5dfd-4d26-8808-a92eb4f983ee");
    private static final String WORKSPACE = "/home/me/repo";
    private static final String QUESTION = "왜 인증 방식이 바뀌었어?";
    private static final int READ_TIMEOUT_SECONDS = 90;

    @Mock
    private McpBindingService bindingService;

    @Mock
    private ProjectService projectService;

    @Mock
    private PlanService planService;

    @Mock
    private McpRateLimiter rateLimiter;

    @Mock
    private AiEngineQueryClient aiEngineQueryClient;

    // ── listProjects ──

    @Test
    @DisplayName("listProjects: 프로젝트가 없으면 오류 아님 + 먼저 만들라는 안내")
    void listProjectsWithNoProjectsGuidesToCreateOne() {
        when(projectService.findProjects(USER_ID)).thenReturn(List.of());

        McpToolOutcome outcome = service().listProjects(USER_ID);

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.text()).contains("프로젝트").contains("만들");
    }

    @Test
    @DisplayName("listProjects: 프로젝트마다 이름과 id를 목록으로 반환")
    void listProjectsReturnsNameAndIdOfEveryProject() {
        when(projectService.findProjects(USER_ID)).thenReturn(List.of(
                project(PROJECT_ID, "Alpha"), project(OTHER_PROJECT_ID, "Beta")));

        McpToolOutcome outcome = service().listProjects(USER_ID);

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.text())
                .contains("Alpha").contains(PROJECT_ID.toString())
                .contains("Beta").contains(OTHER_PROJECT_ID.toString());
    }

    // ── bindProject ──

    @Test
    @DisplayName("bindProject: 연결에 성공하면 프로젝트 이름이 든 확인 문구")
    void bindProjectConfirmsWithProjectName() {
        when(bindingService.bind(USER_ID, WORKSPACE, PROJECT_ID)).thenReturn(project(PROJECT_ID, "Alpha"));

        McpToolOutcome outcome = service().bindProject(USER_ID, WORKSPACE, PROJECT_ID.toString());

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.text()).contains("Alpha");
    }

    @Test
    @DisplayName("bindProject: projectId가 UUID 형식이 아니면 오류 — 연결을 시도하지 않는다")
    void bindProjectRejectsMalformedProjectId() {
        McpToolOutcome outcome = service().bindProject(USER_ID, WORKSPACE, "not-a-uuid");

        assertThat(outcome.isError()).isTrue();
        verifyNoInteractions(bindingService);
    }

    @Test
    @DisplayName("bindProject: 경로가 올바르지 않으면 절대 경로 안내와 함께 오류")
    void bindProjectReportsInvalidWorkspacePath() {
        when(bindingService.bind(USER_ID, "relative/path", PROJECT_ID))
                .thenThrow(new IllegalArgumentException("workspace must be absolute"));

        McpToolOutcome outcome = service().bindProject(USER_ID, "relative/path", PROJECT_ID.toString());

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text()).contains("절대 경로");
    }

    @Test
    @DisplayName("bindProject: 없는 프로젝트와 남의 프로젝트는 같은 문구 — 존재 여부를 드러내지 않는다")
    void bindProjectGivesSameMessageForNotFoundAndForbidden() {
        when(bindingService.bind(USER_ID, WORKSPACE, PROJECT_ID))
                .thenThrow(new NotFoundException("Project not found."));
        when(bindingService.bind(USER_ID, WORKSPACE, OTHER_PROJECT_ID))
                .thenThrow(new ForbiddenException("Forbidden."));

        McpToolOutcome notFound = service().bindProject(USER_ID, WORKSPACE, PROJECT_ID.toString());
        McpToolOutcome forbidden = service().bindProject(USER_ID, WORKSPACE, OTHER_PROJECT_ID.toString());

        assertThat(notFound.isError()).isTrue();
        assertThat(forbidden.isError()).isTrue();
        assertThat(notFound.text()).isEqualTo(forbidden.text());
    }

    // ── ask: 입력 검증 ──

    @Test
    @DisplayName("ask: 질문이 null이거나 공백이면 오류 — 협력자를 부르지 않는다")
    void askRejectsBlankQuestion() {
        McpQueryService service = service();

        assertThat(service.ask(USER_ID, WORKSPACE, null).isError()).isTrue();
        assertThat(service.ask(USER_ID, WORKSPACE, "   ").isError()).isTrue();

        verifyNoCollaborators();
    }

    // ── 공통 흐름 ──

    @Test
    @DisplayName("ask: 성공하면 오류 아님 + 답변 원문, 순서는 resolve → 한도 확인 → 상한 → 한도 기록 → 질의")
    void askRunsFlowInOrderAndReturnsAnswerVerbatim() {
        stubBound();
        stubAllowed();
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("PR #18 때문입니다.", null));

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.text()).isEqualTo("PR #18 때문입니다.");
        InOrder order = inOrder(bindingService, planService, rateLimiter, aiEngineQueryClient);
        order.verify(bindingService).resolve(USER_ID, WORKSPACE);
        order.verify(planService).ensureQueryAllowed(USER_ID);
        order.verify(rateLimiter).acquire(USER_ID);
        order.verify(planService).recordQuery(USER_ID);
        order.verify(aiEngineQueryClient).ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of());
    }

    @Test
    @DisplayName("ask: 경로가 올바르지 않으면 절대 경로 안내와 함께 오류 — 뒤 단계를 부르지 않는다")
    void askReportsInvalidWorkspacePath() {
        when(bindingService.resolve(USER_ID, "relative/path"))
                .thenThrow(new IllegalArgumentException("workspace must be absolute"));

        McpToolOutcome outcome = service().ask(USER_ID, "relative/path", QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text()).contains("절대 경로");
        verifyNoInteractions(planService, rateLimiter, aiEngineQueryClient);
    }

    @Test
    @DisplayName("ask: 프로젝트가 없으면 먼저 만들라는 안내 — 뒤 단계를 부르지 않는다")
    void askWithNoProjectsGuidesToCreateOne() {
        when(bindingService.resolve(USER_ID, WORKSPACE)).thenReturn(new NoProjects());

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text()).contains("프로젝트").contains("만들");
        verifyNoInteractions(planService, rateLimiter, aiEngineQueryClient);
    }

    @Test
    @DisplayName("ask: 폴더가 연결되지 않았으면 프로젝트 목록과 bind_project 호출 지시 — 프로젝트가 2건이어도 질의하지 않는다")
    void askWithUnboundWorkspaceListsProjectsAndInstructsBindProject() {
        when(bindingService.resolve(USER_ID, WORKSPACE)).thenReturn(new Unbound(List.of(
                project(PROJECT_ID, "Alpha"), project(OTHER_PROJECT_ID, "Beta"))));

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text())
                .contains("Alpha").contains(PROJECT_ID.toString())
                .contains("Beta").contains(OTHER_PROJECT_ID.toString())
                .contains("bind_project")
                .contains("사용자");
        verifyNoInteractions(planService, rateLimiter, aiEngineQueryClient);
    }

    @Test
    @DisplayName("ask: 프로젝트가 1건뿐이어도 연결되지 않았으면 질의하지 않고 bind_project를 안내")
    void askWithUnboundSingleProjectStillDoesNotQuery() {
        when(bindingService.resolve(USER_ID, WORKSPACE)).thenReturn(new Unbound(List.of(
                project(PROJECT_ID, "Alpha"))));

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text())
                .contains("Alpha").contains(PROJECT_ID.toString())
                .contains("bind_project");
        verifyNoInteractions(planService, rateLimiter, aiEngineQueryClient);
    }

    @Test
    @DisplayName("ask: 질의 한도를 넘었으면 한도 안내 — 상한·기록·질의를 부르지 않는다")
    void askStopsWhenQueryNotAllowed() {
        stubBound();
        doThrow(new PlanLimitExceededException("limit")).when(planService).ensureQueryAllowed(USER_ID);

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text()).contains("한도");
        verifyNoInteractions(rateLimiter, aiEngineQueryClient);
        verify(planService, never()).recordQuery(USER_ID);
    }

    @Test
    @DisplayName("ask: 분당 상한에 걸리면 남은 초(올림)를 안내 — 한도 기록과 질의를 부르지 않는다")
    void askStopsWhenRateLimitedAndReportsCeilingSeconds() {
        stubBound();
        when(rateLimiter.acquire(USER_ID)).thenReturn(Duration.ofMillis(41_200));

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text()).contains("42");
        verify(planService).ensureQueryAllowed(USER_ID);
        verify(planService, never()).recordQuery(USER_ID);
        verifyNoInteractions(aiEngineQueryClient);
    }

    @Test
    @DisplayName("ask: 기록 단계에서 한도 경합이 나면 한도 안내 — 질의하지 않는다")
    void askStopsWhenRecordQueryHitsLimit() {
        stubBound();
        when(rateLimiter.acquire(USER_ID)).thenReturn(Duration.ZERO);
        doThrow(new PlanLimitExceededException("limit")).when(planService).recordQuery(USER_ID);

        McpToolOutcome outcome = service().ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.text()).contains("한도");
        verifyNoInteractions(aiEngineQueryClient);
    }

    @Test
    @DisplayName("ask: 시간 초과면 설정된 초가 든 시간 초과 문구, 그 밖의 실패와 문구가 다르다")
    void askDistinguishesTimeoutFromGenericFailure() {
        stubBound();
        stubAllowed();
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.timeout("timeout fallback"))
                .thenReturn(AiEngineQueryResult.fallback("error fallback"));
        McpQueryService service = service();

        McpToolOutcome timeout = service.ask(USER_ID, WORKSPACE, QUESTION);
        McpToolOutcome error = service.ask(USER_ID, WORKSPACE, QUESTION);

        assertThat(timeout.isError()).isTrue();
        assertThat(timeout.text()).contains(String.valueOf(READ_TIMEOUT_SECONDS));
        assertThat(error.isError()).isTrue();
        assertThat(error.text()).doesNotContain(String.valueOf(READ_TIMEOUT_SECONDS));
        assertThat(timeout.text()).isNotEqualTo(error.text());
    }

    // ── explainCommit ──

    @Test
    @DisplayName("explainCommit: 질문 템플릿과 commit focus로 같은 흐름을 탄다")
    void explainCommitAsksWithTemplateAndCommitFocus() {
        stubBound();
        stubAllowed();
        String hash = "abc1234def";
        List<EvidenceRef> focus = List.of(new EvidenceRef("commit", hash));
        when(aiEngineQueryClient.ask("커밋 abc1234def가 왜 이렇게 바뀌었는지 근거와 함께 설명해줘",
                PROJECT_ID, List.of(), List.of(), null, focus))
                .thenReturn(AiEngineQueryResult.success("로그인 개편 때문입니다.", null));

        McpToolOutcome outcome = service().explainCommit(USER_ID, WORKSPACE, hash);

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.text()).isEqualTo("로그인 개편 때문입니다.");
        InOrder order = inOrder(bindingService, planService, rateLimiter, aiEngineQueryClient);
        order.verify(bindingService).resolve(USER_ID, WORKSPACE);
        order.verify(planService).ensureQueryAllowed(USER_ID);
        order.verify(rateLimiter).acquire(USER_ID);
        order.verify(planService).recordQuery(USER_ID);
        order.verify(aiEngineQueryClient).ask(
                "커밋 abc1234def가 왜 이렇게 바뀌었는지 근거와 함께 설명해줘",
                PROJECT_ID, List.of(), List.of(), null, focus);
    }

    @Test
    @DisplayName("explainCommit: 16진수 7~40자가 아니면 오류 — 협력자를 부르지 않는다")
    void explainCommitRejectsInvalidHash() {
        McpQueryService service = service();

        assertThat(service.explainCommit(USER_ID, WORKSPACE, "abc123").isError()).isTrue();      // 6자
        assertThat(service.explainCommit(USER_ID, WORKSPACE, "abcdefg").isError()).isTrue();     // 비16진수
        assertThat(service.explainCommit(USER_ID, WORKSPACE, "a".repeat(41)).isError()).isTrue(); // 41자
        assertThat(service.explainCommit(USER_ID, WORKSPACE, null).isError()).isTrue();

        verifyNoCollaborators();
    }

    @Test
    @DisplayName("explainCommit: 7자와 40자 해시는 통과")
    void explainCommitAcceptsBoundaryHashLengths() {
        stubBound();
        stubAllowed();
        when(aiEngineQueryClient.ask(anyString(), any(UUID.class), anyList(), anyList(), any(), anyList()))
                .thenReturn(AiEngineQueryResult.success("답", null));
        McpQueryService service = service();

        assertThat(service.explainCommit(USER_ID, WORKSPACE, "abc1234").isError()).isFalse();
        assertThat(service.explainCommit(USER_ID, WORKSPACE, "0123456789abcdef0123456789abcdef01234567").isError())
                .isFalse();
    }

    // ── 헬퍼 ──

    private void stubBound() {
        when(bindingService.resolve(USER_ID, WORKSPACE)).thenReturn(new Bound(project(PROJECT_ID, "Alpha")));
    }

    private void stubAllowed() {
        when(rateLimiter.acquire(USER_ID)).thenReturn(Duration.ZERO);
    }

    private void verifyNoCollaborators() {
        verifyNoInteractions(bindingService, projectService, planService, rateLimiter, aiEngineQueryClient);
    }

    private Project project(UUID id, String name) {
        User owner = new User("github", USER_ID.toString(), "user@example.com", "User", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        Project project = new Project(owner, name, null);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }

    private McpQueryService service() {
        return new McpQueryService(bindingService, projectService, planService, rateLimiter,
                aiEngineQueryClient,
                new McpOAuthProperties("http://localhost:5173", null, null, null, "/mcp/consent"),
                READ_TIMEOUT_SECONDS);
    }
}
