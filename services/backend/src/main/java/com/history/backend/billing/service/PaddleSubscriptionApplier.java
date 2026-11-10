package com.history.backend.billing.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.history.backend.auth.service.PlanService;
import com.history.backend.billing.BillingProperties;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingSubscriptionOwner;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.common.error.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Paddle 구독 상태 → 우리 DB(billing_subscriptions 캐시 + users.plan) 반영. 웹훅과 Paddle API 조회가 같은
// 규칙으로 반영하도록 한 곳에 모았다. Paddle이 진실의 원천이고 캐시는 사본이라, 상태가 중복·역순으로
// 들어와도 구독 엔티티의 updated_at이 가장 새로운 것으로 수렴한다.
// 호출자 트랜잭션에 합류한다(REQUIRED) — 웹훅은 원장 선점·반영·outcome 기록이 한 번에 커밋돼야 한다.
@Slf4j
@Service
@Transactional
public class PaddleSubscriptionApplier {

    // D1: 연체(past_due) 동안 Pro 유지 — Paddle 권장. 재시도가 끝내 실패하면 Paddle이 canceled를 보낸다.
    private static final Set<String> ACTIVE_LIKE_STATUSES = Set.of("active", "trialing", "past_due");
    private static final Set<String> INACTIVE_LIKE_STATUSES = Set.of("canceled", "paused");

    public enum ApplySource {
        WEBHOOK,
        API
    }

    public enum ApplyOutcome {
        APPLIED,
        STALE,
        UNCHANGED,
        IGNORED,
        UNMATCHED
    }

    public record ApplyResult(ApplyOutcome outcome, String subscriptionId, UUID userId) {
    }

    private final BillingSubscriptionRepository billingSubscriptionRepository;
    private final PlanService planService;
    private final PaddleProperties paddleProperties;
    private final BillingProperties billingProperties;

    public PaddleSubscriptionApplier(
            BillingSubscriptionRepository billingSubscriptionRepository,
            PlanService planService,
            PaddleProperties paddleProperties,
            BillingProperties billingProperties
    ) {
        this.billingSubscriptionRepository = billingSubscriptionRepository;
        this.planService = planService;
        this.paddleProperties = paddleProperties;
        this.billingProperties = billingProperties;
    }

    // fallbackOrderingTime: state.updatedAt이 없을 때만 쓰는 순서 기준(웹훅은 봉투의 occurred_at)
    public ApplyResult apply(PaddleSubscriptionState state, Instant fallbackOrderingTime, ApplySource source) {
        String subscriptionId = state.subscriptionId();

        // id가 없으면 findById(null)이 IllegalArgumentException을 던진다(500 → 무한 재시도) —
        // 가격 검사·사용자 조회보다 먼저 걸러낸다.
        if (subscriptionId == null) {
            log.warn("Paddle 구독 반영: 구독 데이터에 id가 없어 무시. source={}", source);
            return new ApplyResult(ApplyOutcome.IGNORED, null, null);
        }

        // customer_id는 billing_subscriptions에서 NOT NULL이다 — 없는 채로 save하면 제약 위반으로
        // 500 → Paddle이 최대 3일간 재시도하게 된다.
        if (state.customerId() == null) {
            log.warn("Paddle 구독 반영: customer_id가 없어 무시. subscriptionId={}, source={}", subscriptionId, source);
            return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, null);
        }

        // 순서 기준은 구독 엔티티의 updated_at 하나로 통일한다. 봉투의 occurred_at은 알림이 만들어진 시각이라
        // 같은 구독 상태를 가리키는 API 결과와 비교할 수 없다 — updated_at이 없을 때만 대신 쓴다.
        Instant orderingTime = state.updatedAt() != null ? state.updatedAt() : fallbackOrderingTime;
        if (orderingTime == null) {
            log.warn("Paddle 구독 반영: 순서를 정할 시각이 없어 무시. subscriptionId={}, source={}",
                    subscriptionId, source);
            return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, null);
        }

        // 가격 검사가 사용자 찾기보다 먼저 — pro-price-id가 설정돼 있고 일치하는 항목이 없으면 캐시를 읽지도
        // 않는다. items가 없거나 비어 있으면 캐시의 priceId로 대신 검사하므로 그때만 projection을 읽는다.
        List<String> priceIds = state.priceIds();
        boolean hasItems = priceIds != null && !priceIds.isEmpty();
        String proPriceId = paddleProperties.proPriceId();
        boolean proPriceConfigured = proPriceId != null && !proPriceId.isBlank();

        Optional<BillingSubscriptionOwner> owner;
        String priceId;
        if (hasItems) {
            if (proPriceConfigured) {
                // items[0]만 보면 Pro 가격이 두 번째 이후에 올 때(예: 애드온과 함께 결제) 놓친다.
                if (!priceIds.contains(proPriceId)) {
                    return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, null);
                }
                priceId = proPriceId;
            } else {
                priceId = priceIds.get(0);
            }
            owner = billingSubscriptionRepository.findOwnerBySubscriptionId(subscriptionId);
        } else {
            owner = billingSubscriptionRepository.findOwnerBySubscriptionId(subscriptionId);
            priceId = owner.map(BillingSubscriptionOwner::getPriceId).orElse(null);
            if (proPriceConfigured && !proPriceId.equals(priceId)) {
                return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, null);
            }
        }

        UUID userId = resolveUserId(subscriptionId, state.customDataUserId(), owner);
        if (userId == null) {
            log.warn("Paddle 구독 반영: user_id를 찾을 수 없음. subscriptionId={}, source={}", subscriptionId, source);
            return new ApplyResult(ApplyOutcome.UNMATCHED, subscriptionId, null);
        }

        // 구독 캐시는 사용자 잠금을 잡은 뒤에야, 그것도 한 번만 읽는다. 잠금 전에 읽은 사본은 영속성
        // 컨텍스트(1차 캐시)에 남아, 잠금을 기다리는 사이 다른 트랜잭션이 커밋한 더 새로운 행을 가린다.
        try {
            planService.lockForBillingUpdate(userId);
        } catch (NotFoundException e) {
            // 사용자가 파기된 뒤에도 알림은 계속 올 수 있다 — 예외를 전파하지 않고 UNMATCHED로 돌려준다
            return new ApplyResult(ApplyOutcome.UNMATCHED, subscriptionId, userId);
        }
        Optional<BillingSubscription> existing = billingSubscriptionRepository.findById(subscriptionId);

        // 웹훅은 같은 시각이면 반영한다. API는 같은 시각이면 바뀐 게 없는 것이라 UNCHANGED로 끝낸다 —
        // 주기적 동기화가 매번 모든 구독 행을 다시 쓰지 않게 하기 위해서다.
        if (existing.isPresent()) {
            int comparison = existing.get().getLastEventOccurredAt().compareTo(orderingTime);
            if (comparison > 0) {
                return new ApplyResult(ApplyOutcome.STALE, subscriptionId, userId);
            }
            if (comparison == 0 && source == ApplySource.API) {
                return new ApplyResult(ApplyOutcome.UNCHANGED, subscriptionId, userId);
            }
        }

        String status = state.status();
        try {
            if (status != null && ACTIVE_LIKE_STATUSES.contains(status)) {
                Instant expiresAt = resolveExpiry(state);
                if (expiresAt == null) {
                    log.warn("Paddle 구독 반영: 만료 시각을 계산할 수 없어 무시. subscriptionId={}", subscriptionId);
                    return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, userId);
                }
                planService.activatePaid(userId, expiresAt);
            } else if (status != null && INACTIVE_LIKE_STATUSES.contains(status)) {
                boolean hasOtherLiveSubscription = billingSubscriptionRepository
                        .existsByUserIdAndStatusInAndSubscriptionIdNot(
                                userId, List.copyOf(ACTIVE_LIKE_STATUSES), subscriptionId);
                if (!hasOtherLiveSubscription) {
                    planService.downgradeToFree(userId);
                }
            } else {
                // Paddle이 새 상태 값을 추가하는 경우 — 이 분기는 PlanService를 거치지 않아 사용자 없음
                // 보호(noRollbackFor UNMATCHED 처리)가 없다. 파기된 사용자면 캐시 upsert가 user_id FK
                // 위반으로 500·무한 재시도가 되므로, 플랜뿐 아니라 캐시도 건드리지 않고 IGNORED로만 남긴다.
                log.warn("Paddle 구독 반영: 알 수 없는 구독 상태, 플랜·캐시 변경 없이 무시. subscriptionId={}, status={}",
                        subscriptionId, status);
                return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, userId);
            }
            BillingSubscription subscription = new BillingSubscription(
                    subscriptionId, userId, state.customerId(), status, priceId,
                    state.currentPeriodEndsAt(), state.scheduledChangeAction(), state.scheduledChangeEffectiveAt(),
                    state.canceledAt(), orderingTime
            );
            // 행을 통째로 다시 쓰면, 필드가 없는 새 객체가 이미 보낸 안내 표시를 지운다.
            existing.map(BillingSubscription::getRenewalNoticePeriodEnd)
                    .ifPresent(subscription::recordRenewalNoticePeriodEnd);
            billingSubscriptionRepository.save(subscription);
            return new ApplyResult(ApplyOutcome.APPLIED, subscriptionId, userId);
        } catch (NotFoundException e) {
            return new ApplyResult(ApplyOutcome.UNMATCHED, subscriptionId, userId);
        }
    }

    // custom_data.user_id 우선, 없으면 같은 subscription_id의 기존 구독 캐시로 폴백.
    // Paddle은 결제의 custom_data를 구독과 갱신 결제에 복사하므로 보통은 첫 경로로 찾는다 — 폴백은
    // 체크아웃이 custom_data를 싣지 않았거나 운영자가 대시보드에서 구독을 만든 경우를 위한 것이다.
    private UUID resolveUserId(
            String subscriptionId, String customDataUserId, Optional<BillingSubscriptionOwner> owner
    ) {
        UUID cachedUserId = owner.map(BillingSubscriptionOwner::getUserId).orElse(null);
        if (customDataUserId == null) {
            return cachedUserId;
        }
        UUID customUserId;
        try {
            customUserId = UUID.fromString(customDataUserId);
        } catch (IllegalArgumentException e) {
            log.warn("Paddle 구독 반영: custom_data.user_id가 UUID 형식이 아님. subscriptionId={}", subscriptionId);
            return cachedUserId;
        }
        if (cachedUserId != null && !cachedUserId.equals(customUserId)) {
            log.warn("Paddle 구독 반영: custom_data의 사용자와 캐시의 소유자가 다름, custom_data를 따름. "
                    + "subscriptionId={}, customDataUserId={}, cachedUserId={}",
                    subscriptionId, customUserId, cachedUserId);
        }
        return customUserId;
    }

    // current_billing_period.ends_at을 우선하고, 해지 후처럼 null이면 next_billed_at으로 대체한다.
    // 둘 다 없으면 만료를 계산할 수 없다(호출부가 IGNORED로 처리).
    private Instant resolveExpiry(PaddleSubscriptionState state) {
        Instant candidate = state.currentPeriodEndsAt() != null ? state.currentPeriodEndsAt() : state.nextBilledAt();
        return candidate == null ? null : candidate.plus(billingProperties.expiryGrace());
    }
}
