package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.dto.EraseAccount;
import com.history.backend.integration.dto.PrivacyApplyResult;
import com.history.backend.integration.dto.PrivacyDueAccount;
import com.history.backend.integration.dto.RefreshAccount;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.AtlassianPersonalDataReportProperties;
import com.history.backend.jira.service.AtlassianPersonalDataClient;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraPersonalDataReportService: Jira 개인정보 보고 사이클")
class JiraPersonalDataReportServiceTest {

    private static final Duration CYCLE_PERIOD = Duration.ofDays(7);
    private static final Instant NOW = Instant.parse("2026-08-03T03:00:00Z");
    private static final Instant DUE_BEFORE = NOW.minus(CYCLE_PERIOD);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BOT_TOKEN = "bot-access-token";
    private static final JiraPersonalDataReportService.RunSummary ZERO_SUMMARY =
            new JiraPersonalDataReportService.RunSummary(0, 0, 0, 0, 0);

    private static final UUID PROJECT_1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROJECT_3 = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PROJECT_4 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PROJECT_5 = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PROJECT_6 = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Mock
    private AtlassianAppTokenService atlassianAppTokenService;

    @Mock
    private AiEnginePrivacyClient privacyClient;

    @Mock
    private AtlassianPersonalDataClient personalDataClient;

    @Mock
    private JiraTokenService jiraTokenService;

    @Mock
    private JiraClient jiraClient;

    @Mock
    private IntegrationRepository integrationRepository;

    private final AtlassianPersonalDataReportProperties properties = new AtlassianPersonalDataReportProperties(
            true,
            "0 0 3 * * *",
            CYCLE_PERIOD,
            "https://atlassian.test/report-accounts"
    );

    @Test
    @DisplayName("봇 계정 동의 미완(NotFoundException) → warn 후 zero summary 반환, report·apply 미호출")
    void runReportCycleReturnsZeroSummaryWhenBotCredentialMissing() {
        JiraPersonalDataReportService service = service();
        when(atlassianAppTokenService.getAccessToken())
                .thenThrow(new NotFoundException("Atlassian app credential not found."));

        JiraPersonalDataReportService.RunSummary summary = service.runReportCycle();

        assertThat(summary).isEqualTo(ZERO_SUMMARY);
        verifyNoInteractions(privacyClient, personalDataClient, jiraTokenService, jiraClient, integrationRepository);
    }

    @Test
    @DisplayName("보고 대상 계정이 없으면(빈 목록) report를 호출하지 않고 종료한다")
    void runReportCycleSkipsReportWhenNoAccountsAreDue() {
        JiraPersonalDataReportService service = service();
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(List.of());

        JiraPersonalDataReportService.RunSummary summary = service.runReportCycle();

        assertThat(summary).isEqualTo(ZERO_SUMMARY);
        verifyNoInteractions(personalDataClient);
        verify(privacyClient, never()).applyReport(any(), any(), any(), any());
    }

    @Test
    @DisplayName("204(빈 outcome) 응답 → 보고한 전체 accountId가 ok로 apply된다")
    void runReportCycleTreatsEmptyOutcomeAsAllOk() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:ok1", Instant.parse("2026-07-01T00:00:00Z")),
                new PrivacyDueAccount("712020:ok2", Instant.parse("2026-07-02T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of()));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(0, 0, 2));

        service.runReportCycle();

        ArgumentCaptor<List<String>> okCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<EraseAccount>> eraseCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<RefreshAccount>> refreshCaptor = ArgumentCaptor.captor();
        verify(privacyClient).applyReport(eq(NOW), okCaptor.capture(), eraseCaptor.capture(), refreshCaptor.capture());
        assertThat(okCaptor.getValue()).containsExactlyInAnyOrder("712020:ok1", "712020:ok2");
        assertThat(eraseCaptor.getValue()).isEmpty();
        assertThat(refreshCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("closed 응답 → 재조회 없이 closed 사유로 erase한다")
    void runReportCycleErasesClosedAccountsWithoutRequery() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:closed", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of("712020:closed"), List.of()));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(1, 0, 1));

        service.runReportCycle();

        ArgumentCaptor<List<EraseAccount>> eraseCaptor = ArgumentCaptor.captor();
        verify(privacyClient).applyReport(eq(NOW), anyList(), eraseCaptor.capture(), anyList());
        assertThat(eraseCaptor.getValue()).containsExactly(new EraseAccount("712020:closed", "closed"));
        verifyNoInteractions(jiraClient);
        verify(integrationRepository, never()).findAllByProvider(any());
    }

    @Test
    @DisplayName("updated + 첫 연동 재조회 성공 → refresh 확정, 두 번째 연동은 손대지 않고 즉시 중단한다")
    void runReportCycleRefreshesOnFirstSuccessfulRequeryAndStopsImmediately() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:updated1", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of("712020:updated1")));

        Integration firstIntegration = mock(Integration.class);
        Project firstProject = mock(Project.class);
        when(firstProject.getId()).thenReturn(PROJECT_1);
        when(firstIntegration.getProject()).thenReturn(firstProject);
        when(firstIntegration.externalRefValue("cloud_id")).thenReturn("cloud-1");
        when(jiraTokenService.getAccessToken(PROJECT_1)).thenReturn("token-1");
        when(jiraClient.getUser("cloud-1", "712020:updated1", "token-1"))
                .thenReturn(new JiraClient.JiraUser("712020:updated1", "Alice", "alice@example.com"));

        // 두 번째 연동은 아무 stub도 없다 — 첫 연동에서 성공하면 순회가 즉시 중단돼 손도 대지 않아야 한다
        Integration secondIntegration = mock(Integration.class);

        when(integrationRepository.findAllByProvider(IntegrationProvider.JIRA))
                .thenReturn(List.of(firstIntegration, secondIntegration));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(0, 1, 1));

        service.runReportCycle();

        ArgumentCaptor<List<RefreshAccount>> refreshCaptor = ArgumentCaptor.captor();
        verify(privacyClient).applyReport(eq(NOW), anyList(), anyList(), refreshCaptor.capture());
        assertThat(refreshCaptor.getValue())
                .containsExactly(new RefreshAccount("712020:updated1", "Alice", "alice@example.com"));
        verifyNoInteractions(secondIntegration);
    }

    @Test
    @DisplayName("updated + 모든 연동에서 재조회가 확정 실패하면 access_lost 사유로 erase한다")
    void runReportCycleErasesAsAccessLostWhenRequeryFailsForAllIntegrations() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:lost", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of("712020:lost")));

        Integration integration = mock(Integration.class);
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_2);
        when(integration.getProject()).thenReturn(project);
        when(integration.externalRefValue("cloud_id")).thenReturn("cloud-2");
        when(jiraTokenService.getAccessToken(PROJECT_2)).thenReturn("token-2");
        when(jiraClient.getUser("cloud-2", "712020:lost", "token-2"))
                .thenThrow(new UnauthorizedException("Jira user lookup failed."));

        when(integrationRepository.findAllByProvider(IntegrationProvider.JIRA)).thenReturn(List.of(integration));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(1, 0, 1));

        service.runReportCycle();

        ArgumentCaptor<List<EraseAccount>> eraseCaptor = ArgumentCaptor.captor();
        verify(privacyClient).applyReport(eq(NOW), anyList(), eraseCaptor.capture(), anyList());
        assertThat(eraseCaptor.getValue()).containsExactly(new EraseAccount("712020:lost", "access_lost"));
    }

    @Test
    @DisplayName("updated + 재조회가 일시 장애(BadGateway)로만 실패하면 apply에서 완전히 제외되고 skipped로 집계된다 (오삭제 방지)")
    void runReportCycleExcludesAccountFromApplyWhenRequeryOnlyFailsTransiently() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:transient", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of("712020:transient")));

        Integration integration = mock(Integration.class);
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_3);
        when(integration.getProject()).thenReturn(project);
        when(integration.externalRefValue("cloud_id")).thenReturn("cloud-3");
        when(jiraTokenService.getAccessToken(PROJECT_3)).thenReturn("token-3");
        when(jiraClient.getUser("cloud-3", "712020:transient", "token-3"))
                .thenThrow(new BadGatewayException("Jira user lookup request failed."));

        when(integrationRepository.findAllByProvider(IntegrationProvider.JIRA)).thenReturn(List.of(integration));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(0, 0, 0));

        JiraPersonalDataReportService.RunSummary summary = service.runReportCycle();

        ArgumentCaptor<List<String>> okCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<EraseAccount>> eraseCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<RefreshAccount>> refreshCaptor = ArgumentCaptor.captor();
        verify(privacyClient).applyReport(eq(NOW), okCaptor.capture(), eraseCaptor.capture(), refreshCaptor.capture());
        // 일시 장애일 뿐 계정 접근 상실이 확정된 게 아니므로 ok·erase·refresh 어디에도 있으면 안 된다 —
        // 여기서 erase에 섞여 들어가면 아직 유효한 계정의 개인정보를 지우는 실제 오삭제가 된다.
        assertThat(okCaptor.getValue()).doesNotContain("712020:transient");
        assertThat(eraseCaptor.getValue()).extracting(EraseAccount::accountId).doesNotContain("712020:transient");
        assertThat(refreshCaptor.getValue()).extracting(RefreshAccount::accountId).doesNotContain("712020:transient");
        assertThat(summary.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 연동은 토큰 갱신 실패(Unauthorized)로 확정 실패, 다음 연동에서 재조회에 성공하면 refresh로 확정한다")
    void runReportCycleContinuesToNextIntegrationAfterConfirmedFailureAndRefreshesOnSuccess() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:multi", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of("712020:multi")));

        Integration failingIntegration = mock(Integration.class);
        Project failingProject = mock(Project.class);
        when(failingProject.getId()).thenReturn(PROJECT_4);
        when(failingIntegration.getProject()).thenReturn(failingProject);
        when(failingIntegration.externalRefValue("cloud_id")).thenReturn("cloud-4");
        when(jiraTokenService.getAccessToken(PROJECT_4))
                .thenThrow(new UnauthorizedException("Jira refresh token is invalid or revoked."));

        Integration succeedingIntegration = mock(Integration.class);
        Project succeedingProject = mock(Project.class);
        when(succeedingProject.getId()).thenReturn(PROJECT_5);
        when(succeedingIntegration.getProject()).thenReturn(succeedingProject);
        when(succeedingIntegration.externalRefValue("cloud_id")).thenReturn("cloud-5");
        when(jiraTokenService.getAccessToken(PROJECT_5)).thenReturn("token-5");
        when(jiraClient.getUser("cloud-5", "712020:multi", "token-5"))
                .thenReturn(new JiraClient.JiraUser("712020:multi", "Bob", "bob@example.com"));

        when(integrationRepository.findAllByProvider(IntegrationProvider.JIRA))
                .thenReturn(List.of(failingIntegration, succeedingIntegration));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(0, 1, 1));

        service.runReportCycle();

        ArgumentCaptor<List<RefreshAccount>> refreshCaptor = ArgumentCaptor.captor();
        verify(privacyClient).applyReport(eq(NOW), anyList(), anyList(), refreshCaptor.capture());
        assertThat(refreshCaptor.getValue())
                .containsExactly(new RefreshAccount("712020:multi", "Bob", "bob@example.com"));
    }

    @Test
    @DisplayName("보고 요청이 rate limit(RateLimitedException)이면 이번 런을 즉시 중단하고 apply를 호출하지 않는다")
    void runReportCycleAbortsImmediatelyWhenReportIsRateLimited() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:ratelimited", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts);
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenThrow(new AtlassianPersonalDataClient.RateLimitedException());

        JiraPersonalDataReportService.RunSummary summary = service.runReportCycle();

        assertThat(summary).isEqualTo(ZERO_SUMMARY);
        verify(privacyClient, never()).applyReport(any(), any(), any(), any());
    }

    @Test
    @DisplayName("apply 실패(BadGateway) 후 다음 회차에도 동일한 accountId 집합이 반환되면 진전 없음으로 판단해 중단한다 (무한 루프 방지)")
    void runReportCycleStopsWhenSameAccountIdSetRecursAfterApplyFailure() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:stuck", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        // apply가 계속 실패해 pd_reported_at이 찍히지 않으므로 다음 회차도 같은 accountId 집합이 due로 잡힌다
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(dueAccounts);
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of()));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenThrow(new BadGatewayException("ai-engine privacy apply request failed."));

        service.runReportCycle();

        verify(privacyClient, times(2)).fetchDueAccounts(DUE_BEFORE, 90);
        verify(personalDataClient, times(2)).report(anyList(), eq(BOT_TOKEN));
    }

    @Test
    @DisplayName("dueBefore는 now - cyclePeriod로 계산해 조회한다")
    void runReportCyclePassesNowMinusCyclePeriodAsDueBefore() {
        JiraPersonalDataReportService service = service();
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        ArgumentCaptor<Instant> dueBeforeCaptor = ArgumentCaptor.forClass(Instant.class);
        when(privacyClient.fetchDueAccounts(dueBeforeCaptor.capture(), eq(90))).thenReturn(List.of());

        service.runReportCycle();

        assertThat(dueBeforeCaptor.getValue()).isEqualTo(NOW.minus(CYCLE_PERIOD));
    }

    @Test
    @DisplayName("누적 erased가 0보다 크면 런 종료 시 verifyActorNames를 호출하고, 그 예외는 삼킨다")
    void runReportCycleVerifiesActorNamesWhenErasedCountIsPositiveAndSwallowsItsException() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:closed2", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of("712020:closed2"), List.of()));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(1, 0, 1));
        when(privacyClient.verifyActorNames())
                .thenThrow(new BadGatewayException("ai-engine actor name verification request failed."));

        // verifyActorNames의 예외가 삼켜지지 않으면 이 호출 자체가 그대로 실패한다
        service.runReportCycle();

        verify(privacyClient).verifyActorNames();
    }

    @Test
    @DisplayName("누적 erased가 0이면(전원 ok) verifyActorNames를 호출하지 않는다")
    void runReportCycleSkipsVerifyActorNamesWhenNothingWasErased() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:ok3", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of()));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(0, 0, 1));

        service.runReportCycle();

        verify(privacyClient, never()).verifyActorNames();
    }

    @Test
    @DisplayName("pending 연동(cloud_id 없음)은 재조회 순회에서 건너뛰고 getAccessToken을 호출하지 않는다")
    void runReportCycleSkipsPendingIntegrationDuringRequery() {
        JiraPersonalDataReportService service = service();
        List<PrivacyDueAccount> dueAccounts = List.of(
                new PrivacyDueAccount("712020:pendingskip", Instant.parse("2026-07-01T00:00:00Z")));
        when(atlassianAppTokenService.getAccessToken()).thenReturn(BOT_TOKEN);
        when(privacyClient.fetchDueAccounts(DUE_BEFORE, 90)).thenReturn(dueAccounts).thenReturn(List.of());
        when(personalDataClient.report(anyList(), eq(BOT_TOKEN)))
                .thenReturn(new AtlassianPersonalDataClient.ReportOutcome(List.of(), List.of("712020:pendingskip")));

        Integration pendingIntegration = mock(Integration.class);
        when(pendingIntegration.externalRefValue("cloud_id")).thenReturn(null);

        Integration workingIntegration = mock(Integration.class);
        Project workingProject = mock(Project.class);
        when(workingProject.getId()).thenReturn(PROJECT_6);
        when(workingIntegration.getProject()).thenReturn(workingProject);
        when(workingIntegration.externalRefValue("cloud_id")).thenReturn("cloud-6");
        when(jiraTokenService.getAccessToken(PROJECT_6)).thenReturn("token-6");
        when(jiraClient.getUser("cloud-6", "712020:pendingskip", "token-6"))
                .thenReturn(new JiraClient.JiraUser("712020:pendingskip", "Carol", "carol@example.com"));

        when(integrationRepository.findAllByProvider(IntegrationProvider.JIRA))
                .thenReturn(List.of(pendingIntegration, workingIntegration));
        when(privacyClient.applyReport(eq(NOW), anyList(), anyList(), anyList()))
                .thenReturn(new PrivacyApplyResult(0, 1, 1));

        service.runReportCycle();

        verify(pendingIntegration, never()).getProject();
        verify(jiraTokenService, times(1)).getAccessToken(any());
        verify(jiraTokenService).getAccessToken(PROJECT_6);
    }

    private JiraPersonalDataReportService service() {
        return new JiraPersonalDataReportService(
                atlassianAppTokenService,
                privacyClient,
                personalDataClient,
                jiraTokenService,
                jiraClient,
                integrationRepository,
                properties,
                CLOCK
        );
    }
}
