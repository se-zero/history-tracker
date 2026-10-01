package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.billing.service.PaddleApiClient.PaddleSubscriptionSnapshot;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionCancellationService: 탈퇴·파기 전 구독 해지")
class SubscriptionCancellationServiceTest {

    private static final UUID USER_ID = UUID.fromString("d6e4a624-daf3-4561-ac6b-1ca067dd689b");
    private static final Instant PERIOD_END = Instant.parse("2026-10-01T00:00:00Z");

    @Mock
    private BillingSubscriptionRepository subscriptionRepository;

    @Mock
    private PaddleApiClient paddleApiClient;

    @Test
    @DisplayName("active 구독은 기간 끝 해지, past_due·paused는 즉시 해지")
    void cancelUsesNextPeriodForActiveAndImmediateForPastDueOrPaused() {
        SubscriptionCancellationService service = service();
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isCancellableStatuses)))
                .thenReturn(List.of(subscription("sub_active", "active")))
                .thenReturn(List.of(subscription("sub_due", "past_due")))
                .thenReturn(List.of(subscription("sub_paused", "paused")));
        when(paddleApiClient.getSubscription("sub_active"))
                .thenReturn(snapshot("active", null));
        when(paddleApiClient.getSubscription("sub_due"))
                .thenReturn(snapshot("past_due", null));
        when(paddleApiClient.getSubscription("sub_paused"))
                .thenReturn(snapshot("paused", null));

        service.cancelLiveSubscriptions(USER_ID);
        service.cancelLiveSubscriptions(USER_ID);
        service.cancelLiveSubscriptions(USER_ID);

        verify(paddleApiClient).cancelSubscription("sub_active", EffectiveFrom.NEXT_BILLING_PERIOD);
        verify(paddleApiClient).cancelSubscription("sub_due", EffectiveFrom.IMMEDIATELY);
        verify(paddleApiClient).cancelSubscription("sub_paused", EffectiveFrom.IMMEDIATELY);
    }

    @Test
    @DisplayName("Paddle에서 이미 해지됐거나 해지 예약된 구독은 cancel을 다시 부르지 않는다")
    void skipSubscriptionAlreadyCanceledOrScheduled() {
        SubscriptionCancellationService service = service();
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isCancellableStatuses)))
                .thenReturn(List.of(subscription("sub_done", "active"), subscription("sub_sched", "active")));
        when(paddleApiClient.getSubscription("sub_done")).thenReturn(snapshot("canceled", null));
        when(paddleApiClient.getSubscription("sub_sched")).thenReturn(snapshot("active", "cancel"));

        service.cancelLiveSubscriptions(USER_ID);

        verify(paddleApiClient, never()).cancelSubscription(any(), any());
    }

    @Test
    @DisplayName("하나라도 실패하면 BadGatewayException이고 나머지는 해지하지 못한 채로 남는다")
    void failureAbortsRemainingCancellations() {
        SubscriptionCancellationService service = service();
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isCancellableStatuses)))
                .thenReturn(List.of(subscription("sub_ok", "active"), subscription("sub_bad", "active")));
        when(paddleApiClient.getSubscription("sub_ok")).thenReturn(snapshot("active", null));
        when(paddleApiClient.getSubscription("sub_bad"))
                .thenThrow(new BadGatewayException("Paddle subscription request failed."));

        assertThatThrownBy(() -> service.cancelLiveSubscriptions(USER_ID))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle subscription request failed.");
        verify(paddleApiClient).cancelSubscription("sub_ok", EffectiveFrom.NEXT_BILLING_PERIOD);
        verify(paddleApiClient, never()).cancelSubscription(eq("sub_bad"), any());
    }

    @Test
    @DisplayName("해지할 구독이 없으면 Paddle을 부르지 않는다")
    void noSubscriptionsMakesNoPaddleCalls() {
        SubscriptionCancellationService service = service();
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isCancellableStatuses)))
                .thenReturn(List.of());

        service.cancelLiveSubscriptions(USER_ID);

        verifyNoInteractions(paddleApiClient);
    }

    private SubscriptionCancellationService service() {
        return new SubscriptionCancellationService(subscriptionRepository, paddleApiClient);
    }

    private boolean isCancellableStatuses(Collection<String> statuses) {
        return statuses != null
                && statuses.size() == 4
                && statuses.containsAll(List.of("active", "trialing", "past_due", "paused"));
    }

    private PaddleSubscriptionSnapshot snapshot(String status, String scheduledAction) {
        return new PaddleSubscriptionSnapshot(status, scheduledAction, PERIOD_END);
    }

    private BillingSubscription subscription(String subscriptionId, String status) {
        return new BillingSubscription(
                subscriptionId,
                USER_ID,
                "ctm_1",
                status,
                "pri_pro",
                PERIOD_END,
                null,
                null,
                null,
                PERIOD_END
        );
    }
}
