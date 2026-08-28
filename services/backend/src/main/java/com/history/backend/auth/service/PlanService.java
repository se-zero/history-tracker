package com.history.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.domain.UserProviderConnection;
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

// 무료 티어(FREE) 사용량 제한 검증·기록과 PAID 업그레이드를 담당한다. PAID는 전부 무제한이라
// 매 검사가 plan부터 확인해 FREE 전용 조회(연동 이력·질의 카운트 등)를 건너뛴다.
@Service
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

    // 무료 티어 프로젝트 생성 한도(계정당 1개) 검증
    public void ensureProjectCreatable(UUID ownerId) {
        User user = getUser(ownerId);
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

    // provider 연동 성공 기록 — 멱등(이미 기록돼 있으면 아무 일도 하지 않는다)
    public void recordProviderConnected(UUID ownerId, IntegrationProvider provider) {
        if (userProviderConnectionRepository.existsByUserIdAndProvider(ownerId, provider)) {
            return;
        }
        // 존재 조회 없이 FK만 필요한 참조 — 이미 검증을 통과한 ownerId라 실제 로드는 불필요하다
        User userReference = userRepository.getReferenceById(ownerId);
        userProviderConnectionRepository.save(new UserProviderConnection(userReference, ownerId, provider));
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

    // 무료 티어 질의 사용량 1 증가. PAID는 어차피 쓰이지 않는 값이라 건드리지 않는다.
    public void recordQuery(UUID ownerId) {
        User user = getUser(ownerId);
        if (user.getPlan() == Plan.PAID) {
            return;
        }
        user.incrementFreeQueryCount();
        userRepository.save(user);
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
    public void upgradeToPaid(UUID userId, String code) {
        if (upgradeCode.isEmpty() || code == null
                || !MessageDigest.isEqual(
                        upgradeCode.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            throw new PlanLimitExceededException("Invalid upgrade code.");
        }
        User user = getUser(userId);
        user.upgradeToPaid();
        userRepository.save(user);
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
}
