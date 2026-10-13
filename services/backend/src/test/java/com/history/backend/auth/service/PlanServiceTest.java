package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserProviderConnectionRepository;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanService: 무료 티어 사용량 제한 검증·기록·업그레이드")
class PlanServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final String UPGRADE_CODE = "SECRET-UPGRADE-CODE";
    private static final Instant PLAN_EXPIRES_AT = Instant.parse("2026-05-18T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");
    private static final Instant BETA_ENDS_AT = Instant.parse("2026-07-04T00:00:00Z");

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
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(githubIntegration, slackIntegration));

        service.upgradeToPaid(OWNER_ID, UPGRADE_CODE);

        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        assertThat(githubIntegration.isIncrementalEnabled()).isTrue();
        assertThat(slackIntegration.isIncrementalEnabled()).isTrue();
    }

    @Test
    @DisplayName("upgradeToPaid는 findByIdForUpdate로 사용자 행을 잠근다 — 베타 정리 작업과의 순서 역전을 직렬화하고"
            + " findById는 쓰지 않는다")
    void upgradeToPaidLocksUserRow() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of());

        service.upgradeToPaid(OWNER_ID, UPGRADE_CODE);

        verify(userRepository).findByIdForUpdate(OWNER_ID);
        verify(userRepository, never()).findById(any());
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

    @Test
    @DisplayName("PAID 전환 시 이전에 남아있던 planExpiresAt도 함께 초기화된다")
    void upgradeToPaidClearsAnyPreviousPlanExpiresAt() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.FREE, 0, PLAN_EXPIRES_AT);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of());

        service.upgradeToPaid(OWNER_ID, UPGRADE_CODE);

        assertThat(user.getPlanExpiresAt()).isNull();
    }

    // ── activatePaid ──
    // 웹훅이 쓸 "만료 시각 있는 PAID 전환". 스케줄러(downgradeIfExpired)의 재확인 잠금과
    // 같은 행을 두고 경합하므로 findByIdForUpdate로 잠근다(upgradeToPaid의 findById와 다르다).

    @Test
    @DisplayName("FREE 사용자 → PAID 전환 + 주어진 만료 시각 설정 + 소유 연동 전체 incrementalEnabled=true,"
            + " findByIdForUpdate로 잠근다")
    void activatePaidActivatesPaidPlanWithExpiryAndEnablesIncrementalForAllOwnedIntegrations() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.FREE);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, false);
        Integration slackIntegration = integration(IntegrationProvider.SLACK, false);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(githubIntegration, slackIntegration));

        service.activatePaid(OWNER_ID, PLAN_EXPIRES_AT);

        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        assertThat(user.getPlanExpiresAt()).isEqualTo(PLAN_EXPIRES_AT);
        assertThat(githubIntegration.isIncrementalEnabled()).isTrue();
        assertThat(slackIntegration.isIncrementalEnabled()).isTrue();
        verify(userRepository).save(user);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("이미 PAID인 사용자 → 만료 시각이 새 값으로 연장된다")
    void activatePaidExtendsExpiryForAlreadyPaidUser() {
        PlanService service = service(UPGRADE_CODE);
        Instant extendedExpiresAt = PLAN_EXPIRES_AT.plusSeconds(3600);
        User user = user(Plan.PAID, 0, PLAN_EXPIRES_AT);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of());

        service.activatePaid(OWNER_ID, extendedExpiresAt);

        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        assertThat(user.getPlanExpiresAt()).isEqualTo(extendedExpiresAt);
    }

    // ── downgradeIfExpired ──
    // 만료 스케줄러 전용 진입점. 조회(findExpiredPaidUserIds)와 강등 사이에 갱신 알림이 끼어들 수
    // 있어(docs/billing.md §6-5), 잠근 뒤 조건을 반드시 재확인한다 — 그냥 findByIdForUpdate로
    // 잠그기만 하고 무조건 강등하면 방금 갱신된 계정까지 내려가는 결함이 되는데, 이 스위트의
    // "연장된 경우" 케이스가 그걸 잡는다.

    @Test
    @DisplayName("만료 지난 PAID → FREE·질의 횟수 0·만료 null·소유 연동 incremental 꺼짐,"
            + " findByIdForUpdate로 잠근다")
    void downgradeIfExpiredDowngradesPaidUserPastExpiry() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 7, NOW.minusSeconds(60));
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(githubIntegration));

        boolean downgraded = service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(downgraded).isTrue();
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getFreeQueryCount()).isZero();
        assertThat(user.getPlanExpiresAt()).isNull();
        assertThat(githubIntegration.isIncrementalEnabled()).isFalse();
        verify(userRepository).save(user);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("조회 뒤 만료가 now 이후로 연장된 PAID(갱신 알림이 먼저 반영된 경합) → 변경 없음,"
            + " userRepository.save·integrationRepository.saveAll 호출 없음")
    void downgradeIfExpiredDoesNothingWhenExpiryWasExtendedPastNow() {
        PlanService service = service(UPGRADE_CODE);
        Instant extendedExpiresAt = NOW.plusSeconds(60);
        User user = user(Plan.PAID, 7, extendedExpiresAt);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        boolean downgraded = service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(downgraded).isFalse();
        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        assertThat(user.getFreeQueryCount()).isEqualTo(7);
        assertThat(user.getPlanExpiresAt()).isEqualTo(extendedExpiresAt);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("planExpiresAt이 null인 PAID(전환 코드로 만든 무기한 PAID) → 변경 없음")
    void downgradeIfExpiredDoesNothingWhenPlanExpiresAtIsNull() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 0, null);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        boolean downgraded = service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(downgraded).isFalse();
        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("이미 FREE인 사용자 → 변경 없음")
    void downgradeIfExpiredDoesNothingForAlreadyFreeUser() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.FREE, 3);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        boolean downgraded = service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(downgraded).isFalse();
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getFreeQueryCount()).isEqualTo(3);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("경계: planExpiresAt == now → 변경 없음 (isBefore 기준, 같은 시각은 아직 만료가 아니다)")
    void downgradeIfExpiredDoesNothingWhenPlanExpiresAtEqualsNow() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 0, NOW);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        boolean downgraded = service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(downgraded).isFalse();
        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    // ── downgradeToFree ──
    // 웹훅 해지 경로가 쓰는 강제 강등. activatePaid/downgradeIfExpired와 같은 행을 다투므로
    // findByIdForUpdate로 조회한다(과거 findById에서 변경).

    @Test
    @DisplayName("PAID 강등: plan·freeQueryCount·planExpiresAt이 전부 초기화된다, findByIdForUpdate로 잠근다")
    void downgradeToFreeResetsAllFieldsForPaidUser() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 7, PLAN_EXPIRES_AT);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of());

        service.downgradeToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getFreeQueryCount()).isZero();
        assertThat(user.getPlanExpiresAt()).isNull();
        verify(userRepository).save(user);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("PAID 강등 시 소유 연동 전부 incrementalEnabled=false로 저장된다")
    void downgradeToFreeDisablesIncrementalForAllOwnedIntegrations() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        Integration slackIntegration = integration(IntegrationProvider.SLACK, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(githubIntegration, slackIntegration));

        service.downgradeToFree(OWNER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Integration>> integrationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(integrationRepository).saveAll(integrationsCaptor.capture());
        assertThat(integrationsCaptor.getValue())
                .containsExactlyInAnyOrder(githubIntegration, slackIntegration);
        assertThat(integrationsCaptor.getValue())
                .allSatisfy(saved -> assertThat(saved.isIncrementalEnabled()).isFalse());
    }

    @Test
    @DisplayName("소유 연동이 하나도 없어도 예외 없이 끝난다")
    void downgradeToFreeSucceedsWithNoOwnedIntegrations() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of());

        service.downgradeToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        verify(integrationRepository).saveAll(List.of());
    }

    @Test
    @DisplayName("이미 FREE인 사용자에게 부르면 멱등 — freeQueryCount 보존, 연동 조회 자체를 하지 않는다"
            + " (중복 웹훅·스케줄러 경합으로 무료 질의 10회를 공짜로 다시 받는 버그 방지)")
    void downgradeToFreeIsNoOpForAlreadyFreeUser() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.FREE, 7);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        service.downgradeToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getFreeQueryCount()).isEqualTo(7);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("사용자를 못 찾으면 NotFoundException, 연동 조회도 하지 않는다")
    void downgradeToFreeThrowsWhenUserNotFound() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downgradeToFree(OWNER_ID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("전환 코드 PAID(만료 시각 없음)는 무료로 내리고 증분 수집을 끈다")
    void downgradeCodeUserToFreeResetsPlanAndDisablesIncremental() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 7, null);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(githubIntegration));

        service.downgradeCodeUserToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getFreeQueryCount()).isZero();
        assertThat(user.getPlanExpiresAt()).isNull();
        verify(userRepository).save(user);
        assertThat(githubIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("이미 FREE인 사용자의 코드 강등은 아무것도 하지 않는다")
    void downgradeCodeUserToFreeIsNoOpForFreeUser() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.FREE, 4, null);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        service.downgradeCodeUserToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getFreeQueryCount()).isEqualTo(4);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("만료 시각이 있는 PAID(구독)는 코드 강등 409이고 저장하지 않는다")
    void downgradeCodeUserToFreeRejectsSubscriptionPlan() {
        PlanService service = service(UPGRADE_CODE);
        User user = user(Plan.PAID, 0, PLAN_EXPIRES_AT);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.downgradeCodeUserToFree(OWNER_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only upgrade-code plans can be downgraded here.");

        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("코드 강등 대상 사용자가 없으면 NotFoundException")
    void downgradeCodeUserToFreeThrowsWhenUserNotFound() {
        PlanService service = service(UPGRADE_CODE);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downgradeCodeUserToFree(OWNER_ID))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(integrationRepository);
    }

    // ── 베타 기간 무료 Pro ──
    // users.plan은 FREE 그대로 두고 BetaPeriod가 활성인 동안만 Pro 기능을 연다. 프로젝트는 3개까지.

    @Test
    @DisplayName("베타 + FREE + 프로젝트 2개 → 생성 허용 (상한이 1개로 남아 있지 않다)")
    void ensureProjectCreatableAllowsFreeUserBelowBetaLimit() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(projectRepository.countByOwner_Id(OWNER_ID)).thenReturn(2L);

        service.ensureProjectCreatable(OWNER_ID);
    }

    @Test
    @DisplayName("베타 + FREE + 프로젝트 3개 → 베타 메시지로 거부 (무제한이 아니다)")
    void ensureProjectCreatableRejectsFreeUserAtBetaLimit() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(projectRepository.countByOwner_Id(OWNER_ID)).thenReturn(3L);

        assertThatThrownBy(() -> service.ensureProjectCreatable(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessage("Beta allows up to 3 projects.");
    }

    @Test
    @DisplayName("베타 종료 후 + FREE + 프로젝트 1개 → 기존 Free 메시지로 거부")
    void ensureProjectCreatableRejectsFreeUserWithExistingProjectAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(projectRepository.countByOwner_Id(OWNER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.ensureProjectCreatable(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessage("Free plan allows only one project.");
    }

    @Test
    @DisplayName("베타 중에도 PAID는 프로젝트 5개여도 생성 허용 — 베타 상한 3개가 PAID를 묶지 않는다")
    void ensureProjectCreatableAllowsPaidUserBeyondBetaLimitDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID)));

        service.ensureProjectCreatable(OWNER_ID);

        verify(projectRepository, never()).countByOwner_Id(any());
    }

    @Test
    @DisplayName("베타 + FREE + Discord(화이트리스트 밖) → 연동 허용, 이력 조회도 하지 않는다")
    void ensureProviderConnectableAllowsFreeUserForAnyProviderDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.DISCORD);

        verifyNoInteractions(userProviderConnectionRepository);
    }

    @Test
    @DisplayName("베타 + 이력 있는 provider 재연동도 허용 — 이력 조회 자체를 건너뛴다")
    void ensureProviderConnectableAllowsReconnectDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);

        verifyNoInteractions(userProviderConnectionRepository);
    }

    @Test
    @DisplayName("베타 종료 후 FREE + Discord → 다시 거부")
    void ensureProviderConnectableRejectsFreeUserOutsideWhitelistAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThatThrownBy(() -> service.ensureProviderConnectable(OWNER_ID, IntegrationProvider.DISCORD))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("베타 중에도 연동 성공 이력은 남긴다 (종료 후 재연동 규칙이 이력을 본다)")
    void recordProviderConnectedInsertsHistoryDuringBeta() {
        PlanService service = serviceDuringBeta();

        service.recordProviderConnected(OWNER_ID, IntegrationProvider.GITHUB);

        verify(userProviderConnectionRepository)
                .insertIfAbsent(OWNER_ID, IntegrationProvider.GITHUB.value());
    }

    @Test
    @DisplayName("베타 + FREE + 카운트 10 → 질의 허용")
    void ensureQueryAllowedAllowsFreeUserAtLimitDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE, 10)));

        service.ensureQueryAllowed(OWNER_ID);
    }

    @Test
    @DisplayName("베타 종료 후 FREE + 카운트 10 → 질의 거부")
    void ensureQueryAllowedRejectsFreeUserAtLimitAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE, 10)));

        assertThatThrownBy(() -> service.ensureQueryAllowed(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("베타 중 질의 기록은 어떤 SQL도 실행하지 않는다 (카운트가 쌓이면 종료 후 한도가 이미 차 있다)")
    void recordQueryDoesNothingDuringBeta() {
        PlanService service = serviceDuringBeta();

        service.recordQuery(OWNER_ID);

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("베타 종료 후에는 질의 기록이 다시 원자적 증가를 부른다")
    void recordQueryIncrementsFreeUserCountAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(userRepository.incrementFreeQueryCountIfBelowLimit(OWNER_ID, PlanService.FREE_QUERY_LIMIT))
                .thenReturn(1);

        service.recordQuery(OWNER_ID);

        verify(userRepository).incrementFreeQueryCountIfBelowLimit(OWNER_ID, PlanService.FREE_QUERY_LIMIT);
    }

    @Test
    @DisplayName("베타 + FREE → 정밀 재구축 허용")
    void ensurePreciseRebuildAllowedAllowsFreeUserDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        service.ensurePreciseRebuildAllowed(OWNER_ID);
    }

    @Test
    @DisplayName("베타 종료 후 FREE → 정밀 재구축 거부")
    void ensurePreciseRebuildAllowedRejectsFreeUserAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThatThrownBy(() -> service.ensurePreciseRebuildAllowed(OWNER_ID))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    @DisplayName("베타 + FREE → 증분 수집 활성")
    void isIncrementalEnabledReturnsTrueForFreeUserDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThat(service.isIncrementalEnabled(OWNER_ID)).isTrue();
    }

    @Test
    @DisplayName("베타 종료 후 FREE → 증분 수집 비활성")
    void isIncrementalEnabledReturnsFalseForFreeUserAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));

        assertThat(service.isIncrementalEnabled(OWNER_ID)).isFalse();
    }

    // 베타 중 강등: 플랜은 FREE로 내려가도 증분 수집은 켜진 채 남는다(목표값 = 베타 활성 && 미탈퇴).
    // 베타가 끝나는 시점의 일괄 정리는 이 변경 범위 밖이다.

    @Test
    @DisplayName("베타 중 downgradeToFree: FREE로 내리되 켜진 연동은 켜진 채 남고, 꺼진 연동은 켜진다")
    void downgradeToFreeKeepsIncrementalEnabledDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.PAID, 7, PLAN_EXPIRES_AT);
        Integration enabledIntegration = integration(IntegrationProvider.GITHUB, true);
        Integration disabledIntegration = integration(IntegrationProvider.SLACK, false);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(enabledIntegration, disabledIntegration));

        service.downgradeToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(enabledIntegration.isIncrementalEnabled()).isTrue();
        assertThat(disabledIntegration.isIncrementalEnabled()).isTrue();
        verify(integrationRepository).saveAll(List.of(enabledIntegration, disabledIntegration));
    }

    @Test
    @DisplayName("베타 중 downgradeIfExpired: FREE로 내리되 켜진 연동은 켜진 채 남고, 꺼진 연동은 켜진다")
    void downgradeIfExpiredKeepsIncrementalEnabledDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.PAID, 7, NOW.minusSeconds(60));
        Integration enabledIntegration = integration(IntegrationProvider.GITHUB, true);
        Integration disabledIntegration = integration(IntegrationProvider.SLACK, false);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(enabledIntegration, disabledIntegration));

        boolean downgraded = service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(downgraded).isTrue();
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(enabledIntegration.isIncrementalEnabled()).isTrue();
        assertThat(disabledIntegration.isIncrementalEnabled()).isTrue();
    }

    @Test
    @DisplayName("베타 중 downgradeCodeUserToFree: FREE로 내리되 켜진 연동은 켜진 채 남고, 꺼진 연동은 켜진다")
    void downgradeCodeUserToFreeKeepsIncrementalEnabledDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.PAID, 7, null);
        Integration enabledIntegration = integration(IntegrationProvider.GITHUB, true);
        Integration disabledIntegration = integration(IntegrationProvider.SLACK, false);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID))
                .thenReturn(List.of(enabledIntegration, disabledIntegration));

        service.downgradeCodeUserToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(enabledIntegration.isIncrementalEnabled()).isTrue();
        assertThat(disabledIntegration.isIncrementalEnabled()).isTrue();
    }

    @Test
    @DisplayName("베타 중이라도 탈퇴(soft delete) 사용자의 downgradeToFree는 연동을 끈다")
    void downgradeToFreeDisablesIncrementalForDeletedUserDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.PAID, 0, PLAN_EXPIRES_AT);
        user.softDelete(NOW);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(githubIntegration));

        service.downgradeToFree(OWNER_ID);

        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(githubIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("베타 중이라도 탈퇴 사용자의 downgradeIfExpired는 연동을 끈다")
    void downgradeIfExpiredDisablesIncrementalForDeletedUserDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.PAID, 0, NOW.minusSeconds(60));
        user.softDelete(NOW);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(githubIntegration));

        service.downgradeIfExpired(OWNER_ID, NOW);

        assertThat(githubIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("베타 중이라도 탈퇴 사용자의 downgradeCodeUserToFree는 연동을 끈다")
    void downgradeCodeUserToFreeDisablesIncrementalForDeletedUserDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.PAID, 0, null);
        user.softDelete(NOW);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(githubIntegration));

        service.downgradeCodeUserToFree(OWNER_ID);

        assertThat(githubIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("베타 종료 후 downgradeToFree는 기존처럼 연동을 끈다")
    void downgradeToFreeDisablesIncrementalAfterBeta() {
        PlanService service = serviceAfterBeta();
        User user = user(Plan.PAID, 0, PLAN_EXPIRES_AT);
        Integration githubIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(githubIntegration));

        service.downgradeToFree(OWNER_ID);

        assertThat(githubIntegration.isIncrementalEnabled()).isFalse();
    }

    // ── 베타 증분 수집 정리 (reconcileFreeIncremental / findIncrementalReconcileCandidates) ──
    // pipeline-worker는 요금제를 보지 않고 integrations.incremental_enabled만 읽는다. FREE 사용자의 이 값을
    // 목표값(베타 활성 && 미탈퇴)에 맞추되, 사용자 행을 잠그고 FREE를 재확인해 방금 PAID가 된 사용자는 건드리지 않는다.

    @Test
    @DisplayName("베타 중 FREE의 꺼진 연동 → 켜지고 true 반환, 사용자 행을 잠근다")
    void reconcileFreeIncrementalEnablesDisabledIntegrationDuringBeta() {
        PlanService service = serviceDuringBeta();
        Integration disabledIntegration = integration(IntegrationProvider.GITHUB, false);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(disabledIntegration));

        boolean changed = service.reconcileFreeIncremental(OWNER_ID);

        assertThat(changed).isTrue();
        assertThat(disabledIntegration.isIncrementalEnabled()).isTrue();
        verify(integrationRepository).saveAll(List.of(disabledIntegration));
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("베타 종료 후 FREE의 켜진 연동 → 꺼지고 true 반환")
    void reconcileFreeIncrementalDisablesEnabledIntegrationAfterBeta() {
        PlanService service = serviceAfterBeta();
        Integration enabledIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(enabledIntegration));

        boolean changed = service.reconcileFreeIncremental(OWNER_ID);

        assertThat(changed).isTrue();
        assertThat(enabledIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("베타 중이라도 탈퇴 FREE 사용자의 켜진 연동은 꺼지고 true 반환")
    void reconcileFreeIncrementalDisablesEnabledIntegrationOfDeletedUserDuringBeta() {
        PlanService service = serviceDuringBeta();
        User user = user(Plan.FREE);
        user.softDelete(NOW);
        Integration enabledIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(enabledIntegration));

        boolean changed = service.reconcileFreeIncremental(OWNER_ID);

        assertThat(changed).isTrue();
        assertThat(enabledIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("이미 목표값과 같으면 false 반환 — 베타 중 켜진 연동은 그대로")
    void reconcileFreeIncrementalReturnsFalseWhenAlreadyEnabledDuringBeta() {
        PlanService service = serviceDuringBeta();
        Integration enabledIntegration = integration(IntegrationProvider.GITHUB, true);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(enabledIntegration));

        assertThat(service.reconcileFreeIncremental(OWNER_ID)).isFalse();
        assertThat(enabledIntegration.isIncrementalEnabled()).isTrue();
    }

    @Test
    @DisplayName("이미 목표값과 같으면 false 반환 — 베타 종료 후 꺼진 연동은 그대로")
    void reconcileFreeIncrementalReturnsFalseWhenAlreadyDisabledAfterBeta() {
        PlanService service = serviceAfterBeta();
        Integration disabledIntegration = integration(IntegrationProvider.SLACK, false);
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.FREE)));
        when(integrationRepository.findAllByProject_Owner_Id(OWNER_ID)).thenReturn(List.of(disabledIntegration));

        assertThat(service.reconcileFreeIncremental(OWNER_ID)).isFalse();
        assertThat(disabledIntegration.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("잠근 뒤 보니 PAID(조회 뒤 결제·코드 전환이 먼저 반영된 경합) → false, 연동 저장소와 상호작용하지 않는다")
    void reconcileFreeIncrementalSkipsUserWhoBecamePaidAfterCandidateQuery() {
        PlanService service = serviceAfterBeta();
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.of(user(Plan.PAID)));

        boolean changed = service.reconcileFreeIncremental(OWNER_ID);

        assertThat(changed).isFalse();
        verifyNoInteractions(integrationRepository);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("정리 대상 사용자가 없으면 NotFoundException, 연동 저장소와 상호작용하지 않는다")
    void reconcileFreeIncrementalThrowsWhenUserNotFound() {
        PlanService service = serviceDuringBeta();
        when(userRepository.findByIdForUpdate(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reconcileFreeIncremental(OWNER_ID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(integrationRepository);
    }

    @Test
    @DisplayName("후보 조회는 베타 중에는 betaActive=true를 repository에 넘긴다")
    void findIncrementalReconcileCandidatesPassesTrueDuringBeta() {
        PlanService service = serviceDuringBeta();
        when(integrationRepository.findFreeOwnerIdsWithIncrementalDrift(true)).thenReturn(List.of(OWNER_ID));

        assertThat(service.findIncrementalReconcileCandidates()).containsExactly(OWNER_ID);

        verify(integrationRepository).findFreeOwnerIdsWithIncrementalDrift(true);
        verify(integrationRepository, never()).findFreeOwnerIdsWithIncrementalDrift(false);
    }

    @Test
    @DisplayName("후보 조회는 베타 종료 후에는 betaActive=false를 repository에 넘긴다")
    void findIncrementalReconcileCandidatesPassesFalseAfterBeta() {
        PlanService service = serviceAfterBeta();
        when(integrationRepository.findFreeOwnerIdsWithIncrementalDrift(false)).thenReturn(List.of(OWNER_ID));

        assertThat(service.findIncrementalReconcileCandidates()).containsExactly(OWNER_ID);

        verify(integrationRepository).findFreeOwnerIdsWithIncrementalDrift(false);
        verify(integrationRepository, never()).findFreeOwnerIdsWithIncrementalDrift(true);
    }

    @Test
    @DisplayName("findIncrementalReconcileCandidates는 @Transactional(readOnly = true)여야 한다")
    void findIncrementalReconcileCandidatesIsReadOnlyTransactional() throws NoSuchMethodException {
        Method method = PlanService.class.getMethod("findIncrementalReconcileCandidates");

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    // ── noRollbackFor 가드 (결제 웹훅) ──
    // PaddleWebhookService.handle()은 PlanService가 던지는 NotFoundException(파기된 사용자)을
    // 잡아 UNMATCHED로 기록하고 정상 종료한다. 하지만 activatePaid/downgradeToFree가 예외를 던지는
    // 순간, REQUIRED로 합류한 handle()의 바깥 트랜잭션이 rollback-only로 표시된다
    // (globalRollbackOnParticipationFailure, 기본값) — noRollbackFor 없이는 handle()이 예외를
    // 잡아도 커밋 시점에 UnexpectedRollbackException이 나 원장까지 롤백되고 Paddle이 무한
    // 재시도한다. AuthService.refresh의 noRollbackFor = UnauthorizedException과 같은 이유다.
    // Mockito 단위 테스트로는 이 트랜잭션 경계 문제를 재현할 수 없어 리플렉션으로 선언을 직접 확인한다.

    @Test
    @DisplayName("activatePaid는 @Transactional(noRollbackFor = NotFoundException.class)여야 한다")
    void activatePaidDeclaresNoRollbackForNotFoundException() throws NoSuchMethodException {
        Method method = PlanService.class.getMethod("activatePaid", UUID.class, Instant.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(NotFoundException.class);
    }

    @Test
    @DisplayName("downgradeToFree는 @Transactional(noRollbackFor = NotFoundException.class)여야 한다")
    void downgradeToFreeDeclaresNoRollbackForNotFoundException() throws NoSuchMethodException {
        Method method = PlanService.class.getMethod("downgradeToFree", UUID.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(NotFoundException.class);
    }

    private PlanService service(String upgradeCode) {
        return service(upgradeCode, new BetaPeriod("", Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    // NOW 기준 종료 시각이 미래 → 베타 활성
    private PlanService serviceDuringBeta() {
        return service(UPGRADE_CODE, new BetaPeriod(BETA_ENDS_AT.toString(), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    // 시계가 종료 시각을 1초 지난 상태 → 베타 종료
    private PlanService serviceAfterBeta() {
        return service(UPGRADE_CODE, new BetaPeriod(
                BETA_ENDS_AT.toString(), Clock.fixed(BETA_ENDS_AT.plusSeconds(1), ZoneOffset.UTC)));
    }

    private PlanService service(String upgradeCode, BetaPeriod betaPeriod) {
        return new PlanService(
                userRepository,
                projectRepository,
                integrationRepository,
                userProviderConnectionRepository,
                betaPeriod,
                upgradeCode
        );
    }

    private User user(Plan plan) {
        return user(plan, 0);
    }

    private User user(Plan plan, int freeQueryCount) {
        return user(plan, freeQueryCount, null);
    }

    private User user(Plan plan, int freeQueryCount, Instant planExpiresAt) {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", OWNER_ID);
        ReflectionTestUtils.setField(user, "plan", plan);
        ReflectionTestUtils.setField(user, "freeQueryCount", freeQueryCount);
        ReflectionTestUtils.setField(user, "planExpiresAt", planExpiresAt);
        return user;
    }

    private Integration integration(IntegrationProvider provider, boolean incrementalEnabled) {
        Project project = new Project(user(Plan.FREE), "History Tracker", null);
        Integration integration = Integration.oauth(project, provider, Map.of(), new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(integration, "incrementalEnabled", incrementalEnabled);
        return integration;
    }
}
