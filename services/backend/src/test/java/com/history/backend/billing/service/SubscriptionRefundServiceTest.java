package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyOutcome;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SubscriptionRefundService: 승인된 환불 → Paddle 구독 즉시 해지 → 해지 응답을 applier로 반영.
 * PaddleApiClient·applier는 mock이다 — 호출 순서(PATCH → 해지 → 반영)는 InOrder로, 실패 시 예약 복구는
 * 호출 인자로 확인한다. 이 서비스는 구독 캐시 엔티티를 읽지 않는다(그 보장은 Testcontainers 통합 테스트가 확인).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionRefundService: 승인된 환불 → Paddle 구독 즉시 해지 후 반영")
class SubscriptionRefundServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUBSCRIPTION_ID = "sub_01m38gzr9f7v4h6k2n8p1q3s5t";
    private static final String CUSTOMER_ID = "ctm_01m38h0a3b5c7d9e1f2g4h6j8k";
    private static final String OTHER_CUSTOMER_ID = "ctm_other";
    private static final String PRO_PRICE_ID = "pri_01m38fshra9skx2brd7azsb221";
    private static final String OTHER_PRICE_ID = "pri_other";

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Instant FAR_FUTURE = NOW.plus(Duration.ofDays(20));

    @Mock
    private PaddleApiClient paddleApiClient;

    @Mock
    private PaddleSubscriptionApplier subscriptionApplier;

    // ── 정상 경로: 예약 없음 / 예약 있음 ──

    @Test
    @DisplayName("예약 변경이 없으면 PATCH 없이 즉시 해지 1회 → 해지 응답을 apply(…, null, API)에 넘기고 그 결과를 돌려준다")
    void cancelsImmediatelyAndAppliesCancelResponseWhenNoScheduledChange() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state().build();
        PaddleSubscriptionState canceled = canceledState();
        ApplyResult applied = new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID);
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API)).thenReturn(applied);

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result).isEqualTo(applied);
        InOrder inOrder = inOrder(paddleApiClient, subscriptionApplier);
        inOrder.verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        inOrder.verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY);
        inOrder.verify(subscriptionApplier).apply(canceled, null, ApplySource.API);
        // PATCH·기간 말 해지·추가 반영이 없어야 한다
        verifyNoMoreInteractions(paddleApiClient, subscriptionApplier);
    }

    @Test
    @DisplayName("먼 미래의 해지 예약이 있으면 PATCH(예약 삭제) → 즉시 해지 → 반영 순서다 — 반영은 해지 응답으로 1회뿐이다")
    void removesScheduledChangeBeforeImmediateCancelAndApply() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state().scheduledChange("cancel", FAR_FUTURE).build();
        PaddleSubscriptionState unscheduled = state().build();
        PaddleSubscriptionState canceled = canceledState();
        ApplyResult applied = new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID);
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(unscheduled);
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API)).thenReturn(applied);

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result).isEqualTo(applied);
        InOrder inOrder = inOrder(paddleApiClient, subscriptionApplier);
        inOrder.verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        inOrder.verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
        inOrder.verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY);
        inOrder.verify(subscriptionApplier).apply(canceled, null, ApplySource.API);
        verifyNoMoreInteractions(paddleApiClient, subscriptionApplier);
    }

    @Test
    @DisplayName("pause 같은 다른 예약도 PATCH 다음 즉시 해지한다 — 1시간 보호 장치는 해지 예약에만 걸린다(곧 시작하는 pause도 처리)")
    void removesNonCancelScheduledChangeEvenWhenItStartsSoon() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state().scheduledChange("pause", NOW.plusSeconds(600)).build();
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        InOrder inOrder = inOrder(paddleApiClient, subscriptionApplier);
        inOrder.verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
        inOrder.verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY);
        inOrder.verify(subscriptionApplier).apply(canceled, null, ApplySource.API);
    }

    // ── 보호 장치: 1시간 안에 저절로 끝나는 해지 예약은 건드리지 않는다 ──

    @ParameterizedTest(name = "effective_at = 지금 + {0}초")
    @ValueSource(longs = {-600, 1800, 3600})
    @DisplayName("해지 예약이 지금 + 1시간 이내(이미 지남·정확히 1시간 포함)면 Paddle 변경·반영 없이 IGNORED")
    void ignoresScheduledCancelEndingWithinOneHour(long effectiveOffsetSeconds) {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state()
                .scheduledChange("cancel", NOW.plusSeconds(effectiveOffsetSeconds)).build();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @ParameterizedTest(name = "effective_at = 지금 + {0}초")
    @ValueSource(longs = {3601, 86_400})
    @DisplayName("해지 예약이 1시간보다 조금이라도 뒤면(1시간 1초 뒤 포함) 보호하지 않고 PATCH → 즉시 해지한다")
    void cancelsWhenScheduledCancelEndsMoreThanOneHourLater(long effectiveOffsetSeconds) {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state()
                .scheduledChange("cancel", NOW.plusSeconds(effectiveOffsetSeconds)).build();
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        InOrder inOrder = inOrder(paddleApiClient);
        inOrder.verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
        inOrder.verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY);
    }

    @Test
    @DisplayName("해지 예약에 effective_at이 없으면 현재 기간 끝으로 보호 여부를 판정한다 (1시간 이내면 IGNORED)")
    void usesCurrentPeriodEndWhenScheduledCancelHasNoEffectiveAt() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state()
                .scheduledChange("cancel", null)
                .currentPeriodEndsAt(NOW.plus(ONE_HOUR).minusSeconds(1))
                .build();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("effective_at이 있으면 현재 기간 끝이 1시간 이내여도 effective_at을 따른다 (먼 미래면 PATCH → 즉시 해지)")
    void effectiveAtTakesPriorityOverCurrentPeriodEnd() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state()
                .scheduledChange("cancel", FAR_FUTURE)
                .currentPeriodEndsAt(NOW.plusSeconds(600))
                .build();
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
    }

    @Test
    @DisplayName("해지 예약에 effective_at도 현재 기간 끝도 없으면 시각을 알 수 없어 보호하지 않고 PATCH → 즉시 해지한다")
    void cancelsWhenScheduledCancelHasNoTimeAtAll() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = state()
                .scheduledChange("cancel", null)
                .currentPeriodEndsAt(null)
                .build();
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
    }

    // ── 이미 canceled ──

    @Test
    @DisplayName("이미 canceled면 해지 호출 없이 조회한 상태를 apply(fetched, null, API)로 반영하고 그 결과를 돌려준다")
    void appliesFetchedStateWithoutCancelWhenAlreadyCanceled() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState fetched = canceledState();
        ApplyResult applied = new ApplyResult(ApplyOutcome.UNCHANGED, SUBSCRIPTION_ID, USER_ID);
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(fetched);
        when(subscriptionApplier.apply(fetched, null, ApplySource.API)).thenReturn(applied);

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result).isEqualTo(applied);
        verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        verifyNoMoreInteractions(paddleApiClient);
        verify(subscriptionApplier).apply(fetched, null, ApplySource.API);
        verifyNoMoreInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("이미 canceled여도 다른 고객의 구독이면 IGNORED — 반영하지 않는다")
    void ignoresAlreadyCanceledSubscriptionOfAnotherCustomer() {
        SubscriptionRefundService service = service();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(canceledState());

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, OTHER_CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    // ── 무시 조건: Paddle 변경 호출·반영 없음 ──

    @Test
    @DisplayName("구독 items에 우리 Pro 가격이 없으면 IGNORED — 조회만 하고 변경 호출·반영은 없다")
    void ignoresSubscriptionWithoutProPrice() {
        SubscriptionRefundService service = service();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().priceIds(List.of(OTHER_PRICE_ID)).build());

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("items를 읽을 수 없어(priceIds null) 가격을 확인할 수 없으면 IGNORED")
    void ignoresSubscriptionWhoseItemsAreMissing() {
        SubscriptionRefundService service = service();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(state().priceIds(null).build());

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("Pro 가격이 첫 항목이 아니어도(애드온과 함께 결제) 처리한다")
    void cancelsWhenProPriceIsNotFirstItem() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().priceIds(List.of(OTHER_PRICE_ID, PRO_PRICE_ID)).build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
    }

    @Test
    @DisplayName("조정의 customer_id가 구독의 customer_id와 다르면 IGNORED — 변경 호출·반영 없음")
    void ignoresRefundOfAnotherCustomer() {
        SubscriptionRefundService service = service();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(state().build());

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, OTHER_CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("조정의 customer_id가 null이면 고객 대조를 건너뛰고 처리한다")
    void skipsCustomerCheckWhenAdjustmentCustomerIsNull() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, null);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
    }

    @ParameterizedTest(name = "pro-price-id = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("pro-price-id가 비어 있으면(빈 값은 '모든 가격') Paddle을 조회하지도 않고 IGNORED")
    void ignoresWithoutCallingPaddleWhenProPriceIdIsBlank(String blankProPriceId) {
        SubscriptionRefundService service = service(blankProPriceId);

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNoInteractions(paddleApiClient, subscriptionApplier);
    }

    // ── 실패 처리 ──

    @Test
    @DisplayName("PATCH 뒤 즉시 해지가 실패하면 기간 말 해지로 예약을 복구한 다음 원래 예외를 그대로 다시 던지고, 반영은 하지 않는다")
    void restoresScheduledCancelAndRethrowsWhenImmediateCancelFailsAfterPatch() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState scheduled = state().scheduledChange("cancel", FAR_FUTURE).build();
        BadGatewayException failure = new BadGatewayException("Paddle subscription cancel request failed.");
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(scheduled);
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenThrow(failure);
        // 복구 호출이 없는 결함이면 이 스텁은 안 쓰인다 — 실패 원인이 UnnecessaryStubbing으로 가려지지 않게 lenient
        lenient().when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.NEXT_BILLING_PERIOD))
                .thenReturn(scheduled);

        assertThatThrownBy(() -> service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID))
                .isSameAs(failure);

        InOrder inOrder = inOrder(paddleApiClient);
        inOrder.verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
        inOrder.verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY);
        inOrder.verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.NEXT_BILLING_PERIOD);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("예약 복구까지 실패해도 복구의 예외가 아니라 원래(즉시 해지) 예외를 던진다")
    void rethrowsOriginalExceptionWhenRestoreAlsoFails() {
        SubscriptionRefundService service = service();
        BadGatewayException cancelFailure = new BadGatewayException("immediate cancel failed");
        BadGatewayException restoreFailure = new BadGatewayException("restore failed");
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().scheduledChange("cancel", FAR_FUTURE).build());
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenThrow(cancelFailure);
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.NEXT_BILLING_PERIOD))
                .thenThrow(restoreFailure);

        assertThatThrownBy(() -> service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID))
                .isSameAs(cancelFailure);

        verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.NEXT_BILLING_PERIOD);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("지울 예약이 없었으면(PATCH 안 함) 즉시 해지가 실패해도 복구 호출 없이 예외만 던진다")
    void doesNotRestoreWhenImmediateCancelFailsWithoutPatch() {
        SubscriptionRefundService service = service();
        BadGatewayException failure = new BadGatewayException("immediate cancel failed");
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenThrow(failure);

        assertThatThrownBy(() -> service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID))
                .isSameAs(failure);

        verify(paddleApiClient, times(1)).cancelSubscription(any(), any());
        verify(paddleApiClient, never()).removeScheduledChange(any());
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("즉시 해지는 성공했는데 반영(apply)이 실패하면 예약 복구를 시도하지 않고 그 예외를 던진다")
    void doesNotRestoreWhenApplyFailsAfterSuccessfulCancel() {
        SubscriptionRefundService service = service();
        PaddleSubscriptionState canceled = canceledState();
        IllegalStateException failure = new IllegalStateException("apply failed");
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().scheduledChange("cancel", FAR_FUTURE).build());
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API)).thenThrow(failure);

        assertThatThrownBy(() -> service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID))
                .isSameAs(failure);

        verify(paddleApiClient, never()).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.NEXT_BILLING_PERIOD);
    }

    @Test
    @DisplayName("조회가 실패하면 그 예외를 던지고 변경 호출(PATCH·해지)·반영은 없다")
    void propagatesFetchFailureWithoutAnyChange() {
        SubscriptionRefundService service = service();
        BadGatewayException failure = new BadGatewayException("Paddle subscription request failed.");
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID))
                .isSameAs(failure);

        verify(paddleApiClient).fetchSubscription(SUBSCRIPTION_ID);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("PATCH가 실패하면 그 예외를 던지고 즉시 해지도, 예약 복구도, 반영도 하지 않는다")
    void propagatesPatchFailureWithoutCancelOrRestore() {
        SubscriptionRefundService service = service();
        BadGatewayException failure = new BadGatewayException("Paddle subscription update request failed.");
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().scheduledChange("cancel", FAR_FUTURE).build());
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID))
                .isSameAs(failure);

        verify(paddleApiClient, never()).cancelSubscription(any(), any());
        verifyNoInteractions(subscriptionApplier);
    }

    // ── 생성자 계약: public 3인자 생성자는 시스템 시계를 쓴다 ──

    @Test
    @DisplayName("public 생성자(시계 없음)는 시스템 시계를 쓴다 — 지금 + 10분 뒤 끝나는 해지 예약은 IGNORED")
    void publicConstructorUsesSystemClockToProtectSoonEndingCancel() {
        SubscriptionRefundService service = new SubscriptionRefundService(
                paddleApiClient, subscriptionApplier, properties(PRO_PRICE_ID));
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().scheduledChange("cancel", Instant.now().plus(Duration.ofMinutes(10))).build());

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNoMoreInteractions(paddleApiClient);
        verifyNoInteractions(subscriptionApplier);
    }

    @Test
    @DisplayName("public 생성자(시계 없음)는 시스템 시계를 쓴다 — 지금 + 30일 뒤 해지 예약은 PATCH → 즉시 해지")
    void publicConstructorUsesSystemClockToProcessFarCancel() {
        SubscriptionRefundService service = new SubscriptionRefundService(
                paddleApiClient, subscriptionApplier, properties(PRO_PRICE_ID));
        PaddleSubscriptionState canceled = canceledState();
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID))
                .thenReturn(state().scheduledChange("cancel", Instant.now().plus(Duration.ofDays(30))).build());
        when(paddleApiClient.removeScheduledChange(SUBSCRIPTION_ID)).thenReturn(state().build());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY)).thenReturn(canceled);
        when(subscriptionApplier.apply(canceled, null, ApplySource.API))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        ApplyResult result = service.cancelForApprovedRefund(SUBSCRIPTION_ID, CUSTOMER_ID);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(paddleApiClient).removeScheduledChange(SUBSCRIPTION_ID);
    }

    // ── 헬퍼 ──

    private SubscriptionRefundService service() {
        return service(PRO_PRICE_ID);
    }

    // 고정 시계를 받는 package-private 생성자 — BetaPeriod와 같은 방식
    private SubscriptionRefundService service(String proPriceId) {
        return new SubscriptionRefundService(paddleApiClient, subscriptionApplier, properties(proPriceId), CLOCK);
    }

    private PaddleProperties properties(String proPriceId) {
        return new PaddleProperties("test-secret", Duration.ofSeconds(5), proPriceId, null, "sandbox", null);
    }

    // 즉시 해지의 응답 — 해지 상태, 기간·결제 예정일 없음
    private PaddleSubscriptionState canceledState() {
        return state()
                .status("canceled")
                .currentPeriodEndsAt(null)
                .nextBilledAt(null)
                .canceledAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private StateBuilder state() {
        return new StateBuilder();
    }

    // 기본값은 "우리 Pro 가격의 active 구독, 예약 변경 없음" — 테스트가 바꿀 필드만 덮어쓴다
    private static final class StateBuilder {
        private String status = "active";
        private List<String> priceIds = List.of(PRO_PRICE_ID);
        private String scheduledChangeAction;
        private Instant scheduledChangeEffectiveAt;
        private Instant canceledAt;
        private Instant currentPeriodEndsAt = FAR_FUTURE;
        private Instant nextBilledAt = FAR_FUTURE;
        private Instant updatedAt = NOW.minusSeconds(3600);

        StateBuilder status(String value) {
            this.status = value;
            return this;
        }

        StateBuilder priceIds(List<String> value) {
            this.priceIds = value;
            return this;
        }

        StateBuilder scheduledChange(String action, Instant effectiveAt) {
            this.scheduledChangeAction = action;
            this.scheduledChangeEffectiveAt = effectiveAt;
            return this;
        }

        StateBuilder canceledAt(Instant value) {
            this.canceledAt = value;
            return this;
        }

        StateBuilder currentPeriodEndsAt(Instant value) {
            this.currentPeriodEndsAt = value;
            return this;
        }

        StateBuilder nextBilledAt(Instant value) {
            this.nextBilledAt = value;
            return this;
        }

        StateBuilder updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        PaddleSubscriptionState build() {
            return new PaddleSubscriptionState(
                    SUBSCRIPTION_ID, status, CUSTOMER_ID, priceIds, USER_ID.toString(),
                    scheduledChangeAction, scheduledChangeEffectiveAt, canceledAt,
                    currentPeriodEndsAt, nextBilledAt, updatedAt);
        }
    }
}
