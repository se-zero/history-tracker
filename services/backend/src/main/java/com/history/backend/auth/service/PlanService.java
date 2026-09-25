package com.history.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserProviderConnectionRepository;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 무료 티어(FREE) 사용량 제한 검증·기록과 PAID 업그레이드를 담당한다. PAID는 전부 무제한이라
// 매 검사가 plan부터 확인해 FREE 전용 조회(연동 이력·질의 카운트 등)를 건너뛴다.
@Service
@Transactional
public class PlanService {

    public static final int FREE_QUERY_LIMIT = 10;

    // 무료 티어에서 연동 이력이 재연동을 막는 provider — GitHub·Slack·Jira만 각 1회
    private static final Set<IntegrationProvider> FREE_PROVIDER_WHITELIST =
            Set.of(IntegrationProvider.GITHUB, IntegrationProvider.SLACK, IntegrationProvider.JIRA);

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final IntegrationRepository integrationRepository;
    private final UserProviderConnectionRepository userProviderConnectionRepository;
    private final String upgradeCode;

    // @Value는 필드가 아니라 생성자 파라미터에 붙여야 한다 — UserService와 같은 이유로
    // @RequiredArgsConstructor를 쓰지 않고 명시 생성자로 작성한다.
    public PlanService(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            IntegrationRepository integrationRepository,
            UserProviderConnectionRepository userProviderConnectionRepository,
            @Value("${app.plan.upgrade-code:}") String upgradeCode
    ) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.integrationRepository = integrationRepository;
        this.userProviderConnectionRepository = userProviderConnectionRepository;
        this.upgradeCode = upgradeCode;
    }

    // 무료 티어 프로젝트 생성 한도(계정당 1개) 검증. 호출자(ProjectService.createProject) 트랜잭션에
    // 참여해 사용자 행을 잠근 채로 count→insert가 끝나게 한다. 안 그러면 동시 생성이 둘 다 0개를 본다.
    public void ensureProjectCreatable(UUID ownerId) {
        User user = userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found."));
        if (user.getPlan() == Plan.PAID) {
            return;
        }
        if (projectRepository.countByOwner_Id(ownerId) >= 1) {
            throw new PlanLimitExceededException("Free plan allows only one project.");
        }
    }

    // 무료 티어 provider 연동 한도 검증 — 화이트리스트(GitHub·Slack·Jira) 밖이거나 이미 연동 이력이
    // 있으면(해제 후 재연동 포함) 거부. PAID·화이트리스트 밖은 이력 조회 자체를 하지 않는다.
    public void ensureProviderConnectable(UUID ownerId, IntegrationProvider provider) {
        User user = getUser(ownerId);
        if (user.getPlan() == Plan.PAID) {
            return;
        }
        if (!FREE_PROVIDER_WHITELIST.contains(provider)) {
            throw new PlanLimitExceededException(
                    "Free plan only allows GitHub/Slack/Jira, one each.");
        }
        if (userProviderConnectionRepository.existsByUserIdAndProvider(ownerId, provider)) {
            throw new PlanLimitExceededException(
                    "Free plan does not allow reconnecting a previously used provider.");
        }
    }

    // provider 연동 성공 기록 — 멱등. 동시 첫 연동은 ON CONFLICT DO NOTHING이라 PK 위반으로
    // 트랜잭션이 abort되지 않는다.
    public void recordProviderConnected(UUID ownerId, IntegrationProvider provider) {
        userProviderConnectionRepository.insertIfAbsent(ownerId, provider.value());
    }

    // 무료 티어 질의 총량(10회) 검증
    public void ensureQueryAllowed(UUID ownerId) {
        User user = getUser(ownerId);
        if (user.getPlan() == Plan.PAID) {
            return;
        }
        if (user.getFreeQueryCount() >= FREE_QUERY_LIMIT) {
            throw new PlanLimitExceededException("Free plan query limit exceeded.");
        }
    }

    // 무료 티어 질의 사용량 1 증가. 한도 검사와 증가를 한 SQL로 묶어 동시 질의의 lost update를 막는다.
    // PAID는 WHERE plan='FREE'에 안 걸려 0건이 되고, 그때는 예외 없이 끝낸다.
    public void recordQuery(UUID ownerId) {
        int updated = userRepository.incrementFreeQueryCountIfBelowLimit(ownerId, FREE_QUERY_LIMIT);
        if (updated == 1) {
            return;
        }
        User user = getUser(ownerId);
        if (user.getPlan() == Plan.PAID) {
            return;
        }
        throw new PlanLimitExceededException("Free plan query limit exceeded.");
    }

    // 정밀 재구축(verify=true)은 PAID 전용
    public void ensurePreciseRebuildAllowed(UUID ownerId) {
        User user = getUser(ownerId);
        if (user.getPlan() != Plan.PAID) {
            throw new PlanLimitExceededException("Precise rebuild requires a paid plan.");
        }
    }

    // 증분 수집 허용 여부 — FREE는 최초 수집 이후 재수집을 허용하지 않는다
    public boolean isIncrementalEnabled(UUID ownerId) {
        return getUser(ownerId).getPlan() == Plan.PAID;
    }

    // 공유 업그레이드 코드로 FREE -> PAID 전환. 코드 검증을 가장 먼저 해 불일치 시 어떤 조회·변경도
    // 하지 않는다. 빈 문자열끼리 매치되는 사고를 막기 위해 서버 코드가 미설정이면 timing-safe 비교
    // 자체를 생략하고 무조건 거부한다(InternalServiceAuthenticationFilter와 같은 이유).
    // 플랜 전환과 연동 incremental 재활성은 한 트랜잭션이라 한쪽만 적용된 채로 남지 않는다.
    public void upgradeToPaid(UUID userId, String code) {
        if (upgradeCode.isEmpty() || code == null
                || !MessageDigest.isEqual(
                        upgradeCode.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            throw new PlanLimitExceededException("Invalid upgrade code.");
        }
        User user = getUser(userId);
        user.upgradeToPaid();
        userRepository.save(user);
        enableIncrementalForOwnedIntegrations(userId);
    }

    // 결제 웹훅이 쓸 "만료 시각 있는 PAID 전환". 스케줄러(downgradeIfExpired)의 재확인 잠금과
    // 같은 행을 두고 경합하므로 findByIdForUpdate로 잠근다(upgradeToPaid의 findById와 다르다).
    // noRollbackFor: 호출자인 PaddleWebhookService.handle()도 @Transactional이라 이 메서드가
    // REQUIRED로 그 트랜잭션에 합류한다. 사용자가 파기된 뒤에도 알림은 올 수 있어 여기서
    // NotFoundException을 던지는데, 이 설정이 없으면 handle()이 그 예외를 잡아 UNMATCHED로
    // 커밋하려 해도 rollback-only로 표시된 트랜잭션이라 UnexpectedRollbackException으로 커밋이
    // 실패한다(globalRollbackOnParticipationFailure 기본값) — 그러면 원장까지 롤백되고 Paddle이
    // 무한 재시도한다. AuthService.refresh의 noRollbackFor = UnauthorizedException과 같은 이유다.
    @Transactional(noRollbackFor = NotFoundException.class)
    public void activatePaid(UUID userId, Instant expiresAt) {
        User user = getUserForUpdate(userId);
        user.activatePaidUntil(expiresAt);
        userRepository.save(user);
        enableIncrementalForOwnedIntegrations(userId);
    }

    // 만료 스케줄러 전용 진입점. 조회(findExpiredPaidUserIds)와 강등 사이에 갱신 알림이 끼어들
    // 수 있다(docs/billing.md §6-5) — 잠금만으로는 순서 역전(먼저 조회 → 그 사이 갱신 → 뒤늦게 강등)을
    // 막지 못하므로, 잠근 상태에서 만료 조건을 반드시 재확인한다.
    // 반환값: 실제로 강등했으면 true, 재확인에서 조건 불충족으로 건너뛰었으면 false.
    public boolean downgradeIfExpired(UUID userId, Instant now) {
        User user = getUserForUpdate(userId);
        if (user.getPlan() != Plan.PAID
                || user.getPlanExpiresAt() == null
                || !user.getPlanExpiresAt().isBefore(now)) {
            return false;
        }
        applyDowngradeToFree(user);
        return true;
    }

    // PAID -> FREE 강제 강등 (결제 웹훅 해지 경로 등에서 호출). activatePaid/downgradeIfExpired와
    // 같은 행을 다투므로 findByIdForUpdate로 잠근다. 이미 FREE면 아무 것도 하지 않고 즉시 return한다 —
    // 여기서 강등을 또 적용하면 freeQueryCount가 0으로 리셋돼 중복 웹훅·스케줄러 경합으로 같은
    // 사용자가 두 번 강등될 때 무료 질의 10회를 공짜로 다시 주는 버그가 된다. 연동 조회·저장도 이
    // 조기 return 뒤에 있어 FREE 사용자에겐 아예 일어나지 않는다.
    // noRollbackFor: activatePaid와 같은 이유 — 결제 웹훅 해지 경로(PaddleWebhookService.handle)가
    // 이 메서드를 같은 트랜잭션에서 부르고, 파기된 사용자에 대한 NotFoundException을 잡아
    // UNMATCHED로 커밋한다. 이 설정이 없으면 rollback-only로 표시된 트랜잭션이라 커밋이
    // UnexpectedRollbackException으로 실패한다.
    @Transactional(noRollbackFor = NotFoundException.class)
    public void downgradeToFree(UUID userId) {
        User user = getUserForUpdate(userId);
        if (user.getPlan() != Plan.PAID) {
            return;
        }
        applyDowngradeToFree(user);
    }

    // downgradeIfExpired·downgradeToFree 공용 강등 본체
    private void applyDowngradeToFree(User user) {
        user.downgradeToFree();
        userRepository.save(user);
        List<Integration> integrations = integrationRepository.findAllByProject_Owner_Id(user.getId());
        for (Integration integration : integrations) {
            integration.disableIncremental();
        }
        integrationRepository.saveAll(integrations);
    }

    // upgradeToPaid·activatePaid 공용 — 소유 연동 전체 증분 수집 켜기
    private void enableIncrementalForOwnedIntegrations(UUID userId) {
        List<Integration> integrations = integrationRepository.findAllByProject_Owner_Id(userId);
        for (Integration integration : integrations) {
            integration.enableIncremental();
        }
        integrationRepository.saveAll(integrations);
    }

    private User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found."));
    }

    private User getUserForUpdate(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found."));
    }
}
