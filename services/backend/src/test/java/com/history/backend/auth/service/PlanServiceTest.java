package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserProviderConnectionRepository;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanService: 무료 티어 사용량 제한 검증·기록·업그레이드")
class PlanServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final String UPGRADE_CODE = "SECRET-UPGRADE-CODE";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private UserProviderConnectionRepository userProviderConnectionRepository;

    // ── ensureProjectCreatable ──

    @Test
    @DisplayName("FREE + 프로젝트 0개 → 생성 허용")
    void ensureProjectCreatableAllowsFreeUserWithNoProjects() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(projectRepository.countByOwner_Id(OWNER_ID)).thenReturn(0L);

        service.ensureProjectCreatable(OWNER_ID);
    }

    @Test
    @DisplayName("FREE + 프로젝트 1개 이상 → 생성 거부")
    void ensureProjectCreatableRejectsFreeUserWithExistingProject() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(projectRepository.countByOwner_Id(OWNER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.ensureProjectCreatable(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("PAID + 프로젝트 여러 개 → 생성 허용 (무제한, 개수도 세지 않는다)")
    void ensureProjectCreatableAllowsPaidUserRegardlessOfProjectCount() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID)));

        service.ensureProjectCreatable(OWNER_ID);

        verify(projectRepository, never()).countByOwner_Id(any());
    }

    // ── ensureProviderConnectable ──

    @Test
    @DisplayName("FREE + GitHub(화이트리스트) + 이력 없음 → 연동 허용")
    void ensureProviderConnectableAllowsFreeUserForWhitelistedProviderWithoutHistory() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(userProviderConnectionRepository.existsByUserIdAndProvider(OWNER_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);

        service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);
    }

    @Test
    @DisplayName("FREE + 화이트리스트 밖 provider(Discord) → 거부, 이력 조회도 하지 않는다")
    void ensureProviderConnectableRejectsFreeUserForProviderOutsideWhitelist() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThatThrownBy(() -> service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.DISCORD))
                .isInstanceOf(PlanLimitExceededException.class);

        verifyNoInteractions(userProviderConnectionRepository);
    }

    @Test
    @DisplayName("FREE + GitHub + 이미 이력 있음(해제 후 재연동) → 거부")
    void ensureProviderConnectableRejectsFreeUserReconnectingPreviouslyUsedProvider() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(userProviderConnectionRepository.existsByUserIdAndProvider(OWNER_ID, IntegrationProvider.GITHUB))
                .thenReturn(true);

        assertThatThrownBy(() -> service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("PAID + 아무 provider나 → 연동 허용, 이력 조회도 하지 않는다")
    void ensureProviderConnectableAllowsPaidUserForAnyProvider() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID)));

        service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.NOTION);

        verifyNoInteractions(userProviderConnectionRepository);
    }

    // ── recordProviderConnected ──

    @Test
    @DisplayName("연동 성공 시 provider 이력을 ON CONFLICT insert로 남긴다")
    void recordProviderConnectedInsertsHistoryOnFirstCall() {
        PlanService service = service(UPGRADE_CODE);

        service.recordProviderConnected(OWNER_ID, IntegrationProvider.GITHUB);

        verify(userProviderConnectionRepository)
                .insertIfAbsent(OWNER_ID, IntegrationProvider.GITHUB.value());
    }

    // ── ensureQueryAllowed / recordQuery ──

    @Test
    @DisplayName("FREE + 카운트 9 → 질의 허용")
    void ensureQueryAllowedAllowsFreeUserBelowLimit() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE, 9)));

        service.ensureQueryAllowed(OWNER_ID);
    }

    @Test
    @DisplayName("FREE + 카운트 10 → 질의 거부")
    void ensureQueryAllowedRejectsFreeUserAtLimit() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE, 10)));

        assertThatThrownBy(() -> service.ensureQueryAllowed(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("PAID → 카운트와 무관하게 질의 허용")
    void ensureQueryAllowedAllowsPaidUserRegardlessOfCount() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID, 999)));

        service.ensureQueryAllowed(OWNER_ID);
    }

    @Test
    @DisplayName("FREE 사용자 질의 기록은 한도 안에서 원자적으로 1 증가한다")
    void recordQueryIncrementsFreeUserCountAtomicallyWhenBelowLimit() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.incrementFreeQueryCountIfBelowLimit(OWNER_ID, PlanService.FREE_QUERY_LIMIT))
                .thenReturn(1);

        service.recordQuery(OWNER_ID);

        verify(userRepository).incrementFreeQueryCountIfBelowLimit(OWNER_ID, PlanService.FREE_QUERY_LIMIT);
        verify(userRepository, never()).findById(OWNER_ID);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("PAID 사용자는 원자적 증가가 0건이어도 예외 없이 끝낸다 (FREE 행만 갱신 대상)")
    void recordQueryDoesNotIncrementPaidUserCount() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 0);
        when(userRepository.incrementFreeQueryCountIfBelowLimit(OWNER_ID, PlanService.FREE_QUERY_LIMIT))
                .thenReturn(0);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user));

        service.recordQuery(OWNER_ID);

        assertThat(user.getFreeQueryCount()).isZero();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("FREE + 한도 소진(원자적 증가 0건) → 질의 기록 거부")
    void recordQueryRejectsFreeUserWhenAtomicIncrementUpdatesNothing() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.incrementFreeQueryCountIfBelowLimit(OWNER_ID, PlanService.FREE_QUERY_LIMIT))
                .thenReturn(0);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE, 10)));

        assertThatThrownBy(() -> service.recordQuery(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class);

        verify(userRepository, never()).save(any());
    }

    // ── ensurePreciseRebuildAllowed ──

    @Test
    @DisplayName("FREE → 정밀 재구축 거부")
    void ensurePreciseRebuildAllowedRejectsFreeUser() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThatThrownBy(() -> service.ensurePreciseRebuildAllowed(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("PAID → 정밀 재구축 허용")
    void ensurePreciseRebuildAllowedAllowsPaidUser() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID)));

        service.ensurePreciseRebuildAllowed(OWNER_ID);
    }

    // ── isIncrementalEnabled ──

    @Test
    @DisplayName("FREE → 증분 수집 비활성")
    void isIncrementalEnabledReturnsFalseForFreeUser() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThat(service.isIncrementalEnabled(OWNER_ID)).isFalse();
    }

    @Test
    @DisplayName("PAID → 증분 수집 활성")
    void isIncrementalEnabledReturnsTrueForPaidUser() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID)));

        assertThat(service.isIncrementalEnabled(OWNER_ID)).isTrue();
    }

    // ── upgradeToPaid ──

    @Test
    @DisplayName("올바른 코드 → PAID 전환 + 소유 연동 전체 incrementalEnabled=true로 갱신")
    void upgradeToPaidActivatesPaidPlanAndEnablesIncrementalForAllOwnedIntegrations() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.FREE);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, false);
        Integration slackIntegration = integration(IntegrationProvider.SLACK, false);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(githubIntegration, slackIntegration));

        service.upgradeToPaid(OWNER_ID, UPGRADE_CODE);

        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        assertThat(githubIntegration.isIncrementalEnabled()).isTrue();
        assertThat(slackIntegration.isIncrementalEnabled()).isTrue();
    }

    @Test
    @DisplayName("틀린 코드 → 거부, 사용자·연동 아무것도 조회·변경하지 않는다")
    void upgradeToPaidRejectsWrongCodeWithoutTouchingAnything() {
        PlanService service = service(UPGRADE_CODE);

        assertThatThrownBy(() -> service.upgradeToPaid(OWNER_ID, "WRONG-CODE"))
                .isInstanceOf(PlanLimitExceededException.class);

        verifyNoInteractions(userRepository, integrationRepository);
    }

    @Test
    @DisplayName("서버에 업그레이드 코드가 설정되지 않았으면(빈 문자열) 빈 문자열 요청도 거부한다"
            + " — 빈 문자열끼리 매치되는 회귀 방지")
    void upgradeToPaidRejectsBlankCodeWhenServerCodeIsUnset() {
        PlanService service = service("");

        assertThatThrownBy(() -> service.upgradeToPaid(OWNER_ID, ""))
                .isInstanceOf(PlanLimitExceededException.class);

        verifyNoInteractions(userRepository, integrationRepository);
    }

    private PlanService service(String upgradeCode) {
        return new PlanService(
                userRepository,
                projectRepository,
                integrationRepository,
                userProviderConnectionRepository,
                upgradeCode
        );
    }

    private User user(Plan plan) {
        return user(plan, 0);
    }

    private User user(Plan plan, int freeQueryCount) {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", OWNER_ID);
        ReflectionTestUtils.setField(user, "plan", plan);
        ReflectionTestUtils.setField(user, "freeQueryCount", freeQueryCount);
        return user;
    }

    private Integration integration(IntegrationProvider provider, boolean incrementalEnabled) {
        Project project = new Project(user(Plan.FREE), "History Tracker", null);
        Integration integration = Integration.oauth(project, provider, Map.of(), new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(integration, "incrementalEnabled", incrementalEnabled);
        return integration;
    }
}
