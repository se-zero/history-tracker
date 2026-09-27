package com.history.backend.billing.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.billing.service.PaddleApiClient.PaddleSubscriptionSnapshot;
import org.springframework.stereotype.Service;

// 탈퇴·파기 전에 살아 있는 구독의 다음 결제를 끊는다. 캐시만 보면 알림이 늦을 수 있어
// 건마다 Paddle을 다시 조회하고, 하나라도 실패하면 통째로 실패시킨다 — 일부만 해지된 채로
// 계정을 지우면 남은 구독은 멈출 id가 없어진다.
@Service
public class SubscriptionCancellationService {

    // paused는 결제 화면의 "살아 있는 구독"에는 넣지 않지만, 재개되면 다시 결제되므로 해지 대상에는 넣는다.
    static final List<String> CANCELLABLE_STATUSES = List.of("active", "trialing", "past_due", "paused");

    private final BillingSubscriptionRepository subscriptionRepository;
    private final PaddleApiClient paddleApiClient;

    public SubscriptionCancellationService(
            BillingSubscriptionRepository subscriptionRepository,
            PaddleApiClient paddleApiClient
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.paddleApiClient = paddleApiClient;
    }

    public void cancelLiveSubscriptions(UUID userId) {
        for (BillingSubscription subscription : subscriptionRepository.findByUserIdAndStatusIn(
                userId, CANCELLABLE_STATUSES)) {
            PaddleSubscriptionSnapshot snapshot = paddleApiClient.getSubscription(subscription.getSubscriptionId());
            if ("canceled".equals(snapshot.status()) || "cancel".equals(snapshot.scheduledChangeAction())) {
                continue;
            }
            // past_due·paused는 예약 해지가 거절된다. 즉시 해지하면 미납 청구는 Paddle이 면제한다.
            EffectiveFrom effectiveFrom = "past_due".equals(snapshot.status()) || "paused".equals(snapshot.status())
                    ? EffectiveFrom.IMMEDIATELY
                    : EffectiveFrom.NEXT_BILLING_PERIOD;
            paddleApiClient.cancelSubscription(subscription.getSubscriptionId(), effectiveFrom);
        }
    }
}
