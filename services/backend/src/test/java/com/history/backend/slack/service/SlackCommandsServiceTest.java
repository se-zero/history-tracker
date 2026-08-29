package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.service.PlanService;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.service.AiEngineQueryClient;
import com.history.backend.conversation.service.AiEngineQueryResult;
import com.history.backend.conversation.service.MessageService;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.integration.service.SlackCredential;
import com.history.backend.integration.service.SlackCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlackCommandsService: /why-code 슬래시 커맨드")
class SlackCommandsServiceTest {

    private static final String TIMESTAMP = "1756430400";
    private static final String SIGNATURE = "v0=validsig";
    private static final String TEAM_ID = "T123ABC";
    private static final String USER_ID = "U123XYZ";
    private static final String RESPONSE_URL = "https://hooks.slack.com/commands/T123/123/abc";
    private static final String SEARCHING = "질문을 찾고 있어요. 잠시만 기다려 주세요.";
    private static final String BUSY = "지금은 요청이 많아요. 잠시 후 다시 시도해 주세요.";
    private static final String QUERY_FAILED = "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
    private static final String GATING = "이 워크스페이스를 연결한 계정만 사용할 수 있어요.";
    private static final String SITE = "https://why-code.com";
    private static final String PLAN_LIMIT =
            "무료 플랜의 질문 한도에 도달했어요. https://why-code.com 에서 플랜을 확인해주세요.";
    private static final String QUESTION = "why did auth change?";
    private static final String ANSWER = "OAuth callback was updated.";
    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID PROJECT_ID_2 = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final UUID INTEGRATION_ID = UUID.fromString("2f0f1c2e-9a4e-4f0e-9d1a-6b0f8c3d7a55");
    private static final UUID INTEGRATION_ID_2 = UUID.fromString("3c3c3c3c-3c3c-3c3c-3c3c-3c3c3c3c3c3c");
    private static final byte[] ENCRYPTED = new byte[] {1, 2, 3};
    private static final byte[] CORRUPT = new byte[] {9, 9, 9};

    @Mock
    private SlackSignatureVerifier verifier;

    @Mock
    private IntegrationService integrationService;

    @Mock
    private SlackCredentialCodec slackCredentialCodec;

    @Mock
    private SlackClient slackClient;

    @Mock
    private PlanService planService;

    @Mock
    private AiEngineQueryClient aiEngineQueryClient;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageService messageService;

    @Mock
    private TaskExecutor mockExecutor;

    // SyncTaskExecutor: execute가 호출 스레드에서 동기 실행 — verify가 즉시 가능하다
    private final SyncTaskExecutor executor = new SyncTaskExecutor();

    @Test
    @DisplayName("서명 검증 실패 → UnauthorizedException, 협력자 미호출")
    void handleThrowsUnauthorizedWhenSignatureInvalid() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(false);

        assertThatThrownBy(() -> service.handle(TIMESTAMP, SIGNATURE, body))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack request signature.");
        verifyNoInteractions(
                integrationService,
                slackCredentialCodec,
                slackClient,
                planService,
                aiEngineQueryClient,
                conversationRepository,
                messageService);
    }

    @Test
    @DisplayName("빈 text → HTTP 본문에 사용법, executor·query·response_url 미호출")
    void handleReturnsUsageWhenTextIsBlank() {
        SlackCommandsService service = serviceWith(mockExecutor);
        String body = form(TEAM_ID, USER_ID, "  ", RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertUsage(ack);
        verify(mockExecutor, never()).execute(any());
        verifyNoInteractions(integrationService, slackClient, planService, aiEngineQueryClient);
    }

    @Test
    @DisplayName("help(대소문자 무시, trim) → HTTP 본문에 사용법, executor·query·response_url 미호출")
    void handleReturnsUsageForHelpIgnoringCase() {
        SlackCommandsService service = serviceWith(mockExecutor);
        String body = form(TEAM_ID, USER_ID, " Help ", RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertUsage(ack);
        verify(mockExecutor, never()).execute(any());
        verifyNoInteractions(integrationService, slackClient, planService, aiEngineQueryClient);
    }

    @Test
    @DisplayName("help는 executor를 사용하지 않는다 — executor가 거부해도 사용법을 즉시 반환")
    void handleHelpDoesNotUseExecutor() {
        SlackCommandsService service = serviceWithRejectingExecutor();
        String body = form(TEAM_ID, USER_ID, "help", RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertUsage(ack);
    }

    @Test
    @DisplayName("필수값(team_id) 없으면 200 + 사용법, executor 미호출")
    void handleReturnsUsageWhenTeamIdMissing() {
        SlackCommandsService service = serviceWith(mockExecutor);
        String body = "user_id=" + USER_ID
                + "&text=" + encoded(QUESTION)
                + "&response_url=" + encoded(RESPONSE_URL)
                + "&command=" + encoded("/why-code");
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertUsage(ack);
        verify(mockExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("폼 파싱 실패 시 예외 없이 200 + 사용법, executor 미호출")
    void handleReturnsUsageWhenFormIsMalformed() {
        SlackCommandsService service = serviceWith(mockExecutor);
        String body = "%%%";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertUsage(ack);
        verify(mockExecutor, never()).execute(any());
        verifyNoInteractions(integrationService, slackClient, aiEngineQueryClient);
    }

    @Test
    @DisplayName("질의 → HTTP 본문은 찾는 중이고 executor에 나머지를 맡긴다 (execute가 안 돌면 query는 호출되지 않는다)")
    void handleAcksSearchingAndEnqueuesQueryWithoutRunningIt() {
        SlackCommandsService service = serviceWith(mockExecutor);
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.responseType()).isEqualTo("ephemeral");
        assertThat(ack.text()).isEqualTo(SEARCHING);
        verify(mockExecutor).execute(any());
        verifyNoInteractions(integrationService, slackClient, planService, aiEngineQueryClient);
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("executor가 TaskRejectedException을 던지면 5xx가 아니라 200 + 바쁨 문구 (execute는 응답 전에)")
    void handleReturnsBusyWhenExecutorRejects() {
        SlackCommandsService service = serviceWithRejectingExecutor();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.responseType()).isEqualTo("ephemeral");
        assertThat(ack.text()).isEqualTo(BUSY);
        verifyNoInteractions(integrationService, slackClient, aiEngineQueryClient);
    }

    @Test
    @DisplayName("후보 0건 → response_url에 게이팅 안내와 사이트 링크, query 미호출")
    void handlePostsGatingWhenNoCandidate() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", "U999", ENCRYPTED)));

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.text()).isEqualTo(SEARCHING);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains(GATING).contains(SITE);
        assertThat(textCaptor.getValue()).doesNotContain("Oops").doesNotContain("😅");
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
        verify(slackClient, never()).authTest(any());
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("레거시 행 — auth.test user_id가 커맨드 user_id와 같으면 후보로 넣고 connected_user_id를 백필한 뒤 질의")
    void handleBackfillsLegacyRowWhenAuthTestMatchesAndQueries() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", null, ENCRYPTED)));
        when(slackCredentialCodec.decrypt(ENCRYPTED)).thenReturn(new SlackCredential("xoxp-user", null));
        when(slackClient.authTest("xoxp-user")).thenReturn(USER_ID);
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success(ANSWER, null));

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.text()).isEqualTo(SEARCHING);
        verify(integrationService).backfillSlackConnectedUserId(INTEGRATION_ID, USER_ID);
        verify(aiEngineQueryClient).ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of());
        verify(slackClient).postEphemeral(RESPONSE_URL, ANSWER);
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("레거시 행 — auth.test user_id가 다르면 백필·질의 없이 그 행을 건너뛴다")
    void handleSkipsLegacyRowWhenAuthTestUserDiffers() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", null, ENCRYPTED)));
        when(slackCredentialCodec.decrypt(ENCRYPTED)).thenReturn(new SlackCredential("xoxp-user", null));
        when(slackClient.authTest("xoxp-user")).thenReturn("U-OTHER");

        service.handle(TIMESTAMP, SIGNATURE, body);

        verify(integrationService, never()).backfillSlackConnectedUserId(any(), any());
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains(GATING).contains(SITE);
    }

    @Test
    @DisplayName("레거시 행 — auth.test 실패(null)면 그 행을 건너뛰고 백필·질의 없음")
    void handleSkipsLegacyRowWhenAuthTestFails() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", null, ENCRYPTED)));
        when(slackCredentialCodec.decrypt(ENCRYPTED)).thenReturn(new SlackCredential("xoxp-user", null));
        when(slackClient.authTest("xoxp-user")).thenReturn(null);

        service.handle(TIMESTAMP, SIGNATURE, body);

        verify(integrationService, never()).backfillSlackConnectedUserId(any(), any());
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains(GATING);
    }

    @Test
    @DisplayName("레거시 행 decrypt 실패만 있으면 커맨드 전체가 아니라 게이팅 — 질의 실패 문구가 아니다")
    void handlePostsGatingWhenOnlyLegacyRowDecryptFails() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", null, CORRUPT)));
        when(slackCredentialCodec.decrypt(CORRUPT))
                .thenThrow(new IllegalStateException("Missing Slack credential field: user_token"));

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.text()).isEqualTo(SEARCHING);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains(GATING).contains(SITE);
        assertThat(textCaptor.getValue()).isNotEqualTo(QUERY_FAILED);
        verify(integrationService, never()).backfillSlackConnectedUserId(any(), any());
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
        verify(slackClient, never()).authTest(any());
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("레거시 행 decrypt 실패는 그 행만 건너뛰고, 매칭된 다른 후보는 질의한다")
    void handleQueriesMatchingRowWhenAnotherLegacyDecryptFails() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Broken", null, CORRUPT),
                target(INTEGRATION_ID_2, PROJECT_ID_2, "Alpha", USER_ID, ENCRYPTED)));
        when(slackCredentialCodec.decrypt(CORRUPT))
                .thenThrow(new IllegalStateException("Missing Slack credential field: user_token"));
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID_2, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success(ANSWER, null));

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.text()).isEqualTo(SEARCHING);
        verify(aiEngineQueryClient).ask(QUESTION, PROJECT_ID_2, List.of(), List.of(), null, List.of());
        verify(slackClient).postEphemeral(RESPONSE_URL, ANSWER);
        verify(slackClient, never()).postEphemeral(RESPONSE_URL, QUERY_FAILED);
        verify(integrationService, never()).backfillSlackConnectedUserId(any(), any());
        verify(slackCredentialCodec, never()).decrypt(ENCRYPTED);
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("후보 1건 → 단발 질의(대화 미저장, history 빈 리스트), 성공 답변을 ephemeral로")
    void handleQueriesSingleCandidateWithoutPersistingConversation() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED)));
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success(ANSWER, null));

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.responseType()).isEqualTo("ephemeral");
        assertThat(ack.text()).isEqualTo(SEARCHING);
        InOrder order = Mockito.inOrder(planService, aiEngineQueryClient, slackClient);
        order.verify(planService).ensureQueryAllowed(OWNER_ID);
        order.verify(planService).recordQuery(OWNER_ID);
        order.verify(aiEngineQueryClient).ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of());
        order.verify(slackClient).postEphemeral(RESPONSE_URL, ANSWER);
        verify(slackClient, never()).authTest(any());
        verifyNoInteractions(conversationRepository, messageService);
        verify(slackCredentialCodec, never()).decrypt(any());
    }

    @Test
    @DisplayName("후보 2건 이상이고 [이름]이 없으면 목록을 ephemeral로, 자동 선택·query 없음")
    void handleListsProjectsWhenMultipleCandidatesHaveNoNameSelector() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED),
                target(INTEGRATION_ID_2, PROJECT_ID_2, "Beta", USER_ID, ENCRYPTED)));

        service.handle(TIMESTAMP, SIGNATURE, body);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .contains("Alpha")
                .contains("Beta")
                .contains("/why-code [이름] 질문")
                .doesNotContain("Oops")
                .doesNotContain("😅");
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("후보 2건 이상 — [프로젝트명]이 정확히 하나와 같으면(대소문자 무시) 그 프로젝트로 질의")
    void handleSelectsProjectByBracketNameIgnoringCase() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, "[alpha] " + QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED),
                target(INTEGRATION_ID_2, PROJECT_ID_2, "Beta", USER_ID, ENCRYPTED)));
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success(ANSWER, null));

        service.handle(TIMESTAMP, SIGNATURE, body);

        verify(aiEngineQueryClient).ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of());
        verify(slackClient).postEphemeral(RESPONSE_URL, ANSWER);
        Mockito.verifyNoMoreInteractions(aiEngineQueryClient);
    }

    @Test
    @DisplayName("후보 2건 이상 — [이름]이 0건 매칭이면 목록, query 없음")
    void handleListsProjectsWhenBracketNameMatchesNone() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, "[Gamma] " + QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED),
                target(INTEGRATION_ID_2, PROJECT_ID_2, "Beta", USER_ID, ENCRYPTED)));

        service.handle(TIMESTAMP, SIGNATURE, body);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("/why-code [이름] 질문");
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("후보 2건 이상 — [이름]이 2건 매칭이면 목록, 자동 선택·query 없음")
    void handleListsProjectsWhenBracketNameMatchesTwo() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, "[Alpha] " + QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED),
                target(INTEGRATION_ID_2, PROJECT_ID_2, "ALPHA", USER_ID, ENCRYPTED)));

        service.handle(TIMESTAMP, SIGNATURE, body);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackClient).postEphemeral(eq(RESPONSE_URL), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("/why-code [이름] 질문");
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("한도 초과 → response_url에 무료 플랜 한도 안내, query 미호출, Oops 금지")
    void handlePostsPlanLimitWhenQueryNotAllowed() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED)));
        doThrow(new PlanLimitExceededException("Free plan query limit exceeded."))
                .when(planService).ensureQueryAllowed(OWNER_ID);

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.text()).isEqualTo(SEARCHING);
        verify(slackClient).postEphemeral(RESPONSE_URL, PLAN_LIMIT);
        verify(planService, never()).recordQuery(any());
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("ask fallback → response_url에 질의 실패 문구 (ai-engine fallback 원문을 그대로 쓰지 않는다)")
    void handlePostsQueryFailedWhenAskFallsBack() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED)));
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.fallback("질문을 처리하는 중 오류가 발생했습니다."));

        service.handle(TIMESTAMP, SIGNATURE, body);

        verify(slackClient).postEphemeral(RESPONSE_URL, QUERY_FAILED);
        verify(slackClient, never()).postEphemeral(RESPONSE_URL, "질문을 처리하는 중 오류가 발생했습니다.");
    }

    @Test
    @DisplayName("비동기 질의 예외는 삼키고 200 Ack — response_url로 실패 문구, 컨트롤러 예외로 올리지 않는다")
    void handleAcksEvenWhenAskThrows() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID)).thenReturn(List.of(
                target(INTEGRATION_ID, PROJECT_ID, "Alpha", USER_ID, ENCRYPTED)));
        when(aiEngineQueryClient.ask(QUESTION, PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenThrow(new RuntimeException("ai-engine timeout"));

        SlackCommandAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.text()).isEqualTo(SEARCHING);
        verify(slackClient).postEphemeral(RESPONSE_URL, QUERY_FAILED);
    }

    @Test
    @DisplayName("비동기 매핑 실패도 삼키고 200 — 이미 ack한 뒤라 Slack 재시도가 없다")
    void handleAcksEvenWhenTargetLookupThrows() {
        SlackCommandsService service = service();
        String body = form(TEAM_ID, USER_ID, QUESTION, RESPONSE_URL);
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        when(integrationService.listSlackCommandTargets(TEAM_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.handle(TIMESTAMP, SIGNATURE, body)).doesNotThrowAnyException();
        verify(slackClient).postEphemeral(RESPONSE_URL, QUERY_FAILED);
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
    }

    private SlackCommandsService service() {
        return serviceWith(executor);
    }

    private SlackCommandsService serviceWithRejectingExecutor() {
        return serviceWith(new RejectingTaskExecutor());
    }

    private SlackCommandsService serviceWith(TaskExecutor taskExecutor) {
        return new SlackCommandsService(
                verifier,
                integrationService,
                slackCredentialCodec,
                slackClient,
                planService,
                aiEngineQueryClient,
                conversationRepository,
                messageService,
                taskExecutor);
    }

    private static void assertUsage(SlackCommandAck ack) {
        assertThat(ack.responseType()).isEqualTo("ephemeral");
        assertThat(ack.text()).contains("/why-code").contains("help");
        assertThat(ack.text()).doesNotContain("Oops").doesNotContain("😅");
        assertThat(ack.text()).isNotEqualTo(SEARCHING);
    }

    private static IntegrationService.SlackCommandTarget target(
            UUID integrationId,
            UUID projectId,
            String projectName,
            String connectedUserId,
            byte[] encryptedCredential
    ) {
        return new IntegrationService.SlackCommandTarget(
                integrationId, projectId, projectName, OWNER_ID, connectedUserId, encryptedCredential);
    }

    private static String form(String teamId, String userId, String text, String responseUrl) {
        return "team_id=" + encoded(teamId)
                + "&user_id=" + encoded(userId)
                + "&text=" + encoded(text)
                + "&response_url=" + encoded(responseUrl)
                + "&command=" + encoded("/why-code");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static class RejectingTaskExecutor extends SyncTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new TaskRejectedException("slackCommandsTaskExecutor is full");
        }
    }
}
