package com.history.backend.billing.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyOutcome;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// 승인된 환불 → Paddle 구독 즉시 해지. 환불(adjustment)은 구독 상태를 바꾸지 않아 그대로 두면 환불받은 사용자가
// Pro로 남기 때문에, 서버가 구독을 직접 해지하고 그 응답을 PaddleSubscriptionApplier로 반영해 FREE로 내린다.
// 클래스 트랜잭션을 두지 않는다 — Paddle 호출을 모두 끝낸 뒤에야 applier가 사용자 잠금을 잡게 해, 네트워크를
// 기다리는 동안 잠금을 쥐지 않는다. 구독 캐시 엔티티도 여기서 읽지 않는다: 웹훅 트랜잭션 안에서 먼저 읽으면
// 1차 캐시에 옛 사본이 남아, applier가 "잠근 뒤에 처음 읽는다"는 순서 보장이 깨진다.
@Slf4j
@Service
public class SubscriptionRefundService {

    // Paddle은 다음 결제일 30분 전부터 해지를 거절한다. 그 안쪽으로 끝나는 예약은 건드리지 않고 저절로 끝나게 둔다.
    private static final Duration PROTECTION_WINDOW = Duration.ofHours(1);

    private final PaddleApiClient paddleApiClient;
    private final PaddleSubscriptionApplier subscriptionApplier;
    private final PaddleProperties paddleProperties;
    private final Clock clock;

    @Autowired
    public SubscriptionRefundService(
            PaddleApiClient paddleApiClient,
            PaddleSubscriptionApplier subscriptionApplier,
            PaddleProperties paddleProperties
    ) {
        this(paddleApiClient, subscriptionApplier, paddleProperties, Clock.systemUTC());
    }

    // 테스트 전용 생성자 — 고정 시계로 1시간 보호 판정을 검증한다
    SubscriptionRefundService(
            PaddleApiClient paddleApiClient,
            PaddleSubscriptionApplier subscriptionApplier,
            PaddleProperties paddleProperties,
            Clock clock
    ) {
        this.paddleApiClient = paddleApiClient;
        this.subscriptionApplier = subscriptionApplier;
        this.paddleProperties = paddleProperties;
        this.clock = clock;
    }

    // adjustmentCustomerId: 조정의 customer_id. null이면 고객 대조를 건너뛴다.
    // 실패는 예외로 전파한다 — 웹훅 트랜잭션이 롤백돼 Paddle이 같은 알림을 다시 보낸다.
    public ApplyResult cancelForApprovedRefund(String subscriptionId, String adjustmentCustomerId) {
        // pro-price-id가 비면 "모든 가격"으로 읽혀, 우리 상품이 아닌 구독까지 해지할 수 있다
        String proPriceId = paddleProperties.proPriceId();
        if (proPriceId == null || proPriceId.isBlank()) {
            log.warn("환불 해지: pro-price-id가 설정돼 있지 않아 무시. subscriptionId={}", subscriptionId);
            return ignored(subscriptionId);
        }

        // 웹훅 data의 값을 믿지 않고 Paddle에서 새로 읽어 판단한다
        PaddleSubscriptionState fetched = paddleApiClient.fetchSubscription(subscriptionId);
        if (fetched.priceIds() == null || !fetched.priceIds().contains(proPriceId)) {
            log.info("환불 해지: 우리 Pro 가격의 구독이 아니라 무시. subscriptionId={}", subscriptionId);
            return ignored(subscriptionId);
        }
        if (adjustmentCustomerId != null && !adjustmentCustomerId.equals(fetched.customerId())) {
            log.warn("환불 해지: 조정의 고객과 구독의 고객이 달라 무시. subscriptionId={}", subscriptionId);
            return ignored(subscriptionId);
        }

        if ("canceled".equals(fetched.status())) {
            return subscriptionApplier.apply(fetched, null, ApplySource.API);
        }

        boolean scheduledChangeRemoved = false;
        if (fetched.scheduledChangeAction() != null) {
            if (endsSoonByScheduledCancel(fetched)) {
                log.info("환불 해지: 해지 예약이 1시간 안에 끝나 Paddle을 건드리지 않음. subscriptionId={}",
                        subscriptionId);
                return ignored(subscriptionId);
            }
            paddleApiClient.removeScheduledChange(subscriptionId);
            scheduledChangeRemoved = true;
        }

        PaddleSubscriptionState canceled;
        try {
            canceled = paddleApiClient.cancelSubscription(subscriptionId, EffectiveFrom.IMMEDIATELY);
        } catch (RuntimeException e) {
            // 예약만 지우고 해지가 실패하면, 재시도가 끝내 실패해도 기간 말에는 끝나도록 예약을 되돌려 둔다
            if (scheduledChangeRemoved) {
                restoreScheduledCancel(subscriptionId);
            }
            throw e;
        }
        // 해지는 이미 Paddle에 반영됐다 — 여기서 실패해도 예약을 복구하지 않는다(재시도가 canceled를 보고 반영한다)
        return subscriptionApplier.apply(canceled, null, ApplySource.API);
    }

    // 예약을 지우면 다음 결제일이 되살아나는데, 결제일 30분 안쪽이면 즉시 해지가 거절돼 갱신 결제가 일어난다.
    // 그래서 곧 끝나는(이미 지났지만 Paddle이 아직 처리하지 않은 경우 포함) 해지 예약은 그대로 두고,
    // 1시간 안에 저절로 끝난 뒤 알림·동기화가 내리게 한다. 시각을 알 수 없으면 보호하지 않는다.
    private boolean endsSoonByScheduledCancel(PaddleSubscriptionState fetched) {
        if (!"cancel".equals(fetched.scheduledChangeAction())) {
            return false;
        }
        Instant endsAt = fetched.scheduledChangeEffectiveAt() != null
                ? fetched.scheduledChangeEffectiveAt()
                : fetched.currentPeriodEndsAt();
        return endsAt != null && !endsAt.isAfter(clock.instant().plus(PROTECTION_WINDOW));
    }

    private void restoreScheduledCancel(String subscriptionId) {
        try {
            paddleApiClient.cancelSubscription(subscriptionId, EffectiveFrom.NEXT_BILLING_PERIOD);
            log.warn("환불 해지 실패 뒤 기간 말 해지 예약을 복구함. subscriptionId={}", subscriptionId);
        } catch (RuntimeException e) {
            // 복구 실패가 원래 예외를 가리면 안 된다 — 로그만 남기고 호출부가 원래 예외를 던진다
            log.error("환불 해지 실패 뒤 기간 말 해지 예약 복구도 실패. subscriptionId={}, errorType={}",
                    subscriptionId, e.getClass().getSimpleName());
        }
    }

    private ApplyResult ignored(String subscriptionId) {
        return new ApplyResult(ApplyOutcome.IGNORED, subscriptionId, null);
    }
}
