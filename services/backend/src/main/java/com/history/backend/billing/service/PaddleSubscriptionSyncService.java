package com.history.backend.billing.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Paddle 구독을 조회해 캐시(billing_subscriptions)와 플랜에 맞춘다. 웹훅은 Paddle이 이틀 재전송한 뒤에도
// 사라질 수 있어, 알림만으로는 캐시가 Paddle과 영영 어긋날 수 있다(해지 유실 → 재구독 409, 갱신 유실 →
// 잘못된 강등, 생성 유실 → 결제하고도 FREE). 반영 규칙은 갖지 않고 applier에 넘긴다.
// 이 클래스에는 @Transactional을 붙이지 않는다 — 건마다 applier가 자기 트랜잭션을 가져야 한 건의 실패가
// 앞서 반영한 건을 되돌리지 않고, Paddle을 기다리는 동안 DB 커넥션을 쥐지 않는다.
@Slf4j
@Service
public class PaddleSubscriptionSyncService {

    public record SyncSummary(int applied, int unchanged, int stale, int ignored, int unmatched, int failed,
                              int notFound) {
    }

    private final PaddleApiClient paddleApiClient;
    private final PaddleSubscriptionApplier applier;
    private final BillingSubscriptionRepository billingSubscriptionRepository;
    private final PaddleProperties paddleProperties;

    public PaddleSubscriptionSyncService(
            PaddleApiClient paddleApiClient,
            PaddleSubscriptionApplier applier,
            BillingSubscriptionRepository billingSubscriptionRepository,
            PaddleProperties paddleProperties
    ) {
        this.paddleApiClient = paddleApiClient;
        this.applier = applier;
        this.billingSubscriptionRepository = billingSubscriptionRepository;
        this.paddleProperties = paddleProperties;
    }

    // 전체 동기화. 목록 조회가 실패하면 예외를 던지고(다음 주기에 다시 한다), 구독 한 건의 반영 실패는
    // failed로 세고 계속한다.
    public SyncSummary syncAll() {
        String proPriceId = paddleProperties.proPriceId();
        if (isBlank(paddleProperties.apiKey()) || isBlank(proPriceId)) {
            log.info("Paddle 구독 동기화 건너뜀: API 키 또는 pro-price-id가 설정되지 않음");
            return new SyncSummary(0, 0, 0, 0, 0, 0, 0);
        }

        Tally tally = new Tally();

        // 1) 우리 가격의 살아 있는 구독 전체. 캐시에 없는 구독(생성 알림 유실)도 여기서 들어온다.
        Set<String> listedIds = new HashSet<>();
        for (PaddleSubscriptionState state : paddleApiClient.listLiveSubscriptions(proPriceId)) {
            listedIds.add(state.subscriptionId());
            apply(state, tally);
        }

        // 2) 캐시에는 살아 있는데 1)에 없는 구독은 해지·종료됐을 수 있다. 목록에는 상태 필터가 걸려 있어
        // 해지된 구독이 빠지므로, ID로 따로 받아 바뀐 상태를 반영한다.
        List<String> missingIds = billingSubscriptionRepository
                .findSubscriptionIdsByStatusIn(PaddleSubscriptionStatuses.NON_TERMINAL).stream()
                .filter(id -> !listedIds.contains(id))
                .toList();
        if (!missingIds.isEmpty()) {
            Set<String> returnedIds = new HashSet<>();
            for (PaddleSubscriptionState state : paddleApiClient.listSubscriptionsByIds(missingIds)) {
                returnedIds.add(state.subscriptionId());
                apply(state, tally);
            }
            // Paddle이 돌려주지 않은 구독은 캐시를 그대로 둔다 — 응답에 없다는 것만으로 해지됐다고 단정할 수 없다
            tally.notFound = (int) missingIds.stream().filter(id -> !returnedIds.contains(id)).count();
        }

        SyncSummary summary = tally.toSummary();
        // 구독마다 남기지 않고 요약 한 줄만 남긴다: 사용자를 못 찾는 구독은 매시간 같은 결과로 반복돼
        // 행마다 경고하면 로그가 같은 줄로 쌓인다.
        log.info("Paddle 구독 동기화 완료. applied={} unchanged={} stale={} ignored={} unmatched={} failed={} notFound={}",
                summary.applied(), summary.unchanged(), summary.stale(), summary.ignored(),
                summary.unmatched(), summary.failed(), summary.notFound());
        return summary;
    }

    // 결제 직전 재확인용. 호출자가 결과로 판단하므로 조회·반영 실패를 삼키지 않고 그대로 던진다.
    public void refresh(Collection<String> subscriptionIds) {
        for (PaddleSubscriptionState state : paddleApiClient.listSubscriptionsByIds(subscriptionIds)) {
            applier.apply(state, null, ApplySource.API);
        }
    }

    // 순서 기준은 구독 엔티티의 updated_at이라 봉투 시각 같은 대체 기준은 넘기지 않는다
    private void apply(PaddleSubscriptionState state, Tally tally) {
        try {
            ApplyResult result = applier.apply(state, null, ApplySource.API);
            tally.count(result.outcome());
        } catch (RuntimeException exception) {
            tally.failed++;
            log.warn("Paddle 구독 동기화: 구독 반영 실패. subscriptionId={}", state.subscriptionId(), exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class Tally {
        int applied;
        int unchanged;
        int stale;
        int ignored;
        int unmatched;
        int failed;
        int notFound;

        void count(PaddleSubscriptionApplier.ApplyOutcome outcome) {
            switch (outcome) {
                case APPLIED -> applied++;
                case UNCHANGED -> unchanged++;
                case STALE -> stale++;
                case IGNORED -> ignored++;
                case UNMATCHED -> unmatched++;
            }
        }

        SyncSummary toSummary() {
            return new SyncSummary(applied, unchanged, stale, ignored, unmatched, failed, notFound);
        }
    }
}
