package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.service.PlanService;
import com.history.backend.billing.BillingProperties;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingSubscriptionOwner;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyOutcome;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import com.history.backend.common.error.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PaddleSubscriptionApplier: 구독 상태(웹훅·API 공용) → 캐시(billing_subscriptions)·플랜 반영.
 * 핵심 계약은 "사용자 잠금 → 캐시 읽기(1회) → 순서 비교 → 반영" 순서와 순서 기준(updated_at)이다.
 * PlanService·리포지토리는 mock이라 잠금 순서는 InOrder로, 실제 경합은 별도 통합 테스트가 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaddleSubscriptionApplier: 구독 상태 반영 (잠금 순서·순서 기준·출처별 비교)")
class PaddleSubscriptionApplierTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SUBSCRIPTION_ID = "sub_01m38gzr9f7v4h6k2n8p1q3s5t";
    private static final String CUSTOMER_ID = "ctm_01m38h0a3b5c7d9e1f2g4h6j8k";
    private static final String PRICE_ID = "pri_01m38fshra9skx2brd7azsb221";
    private static final String OTHER_PRICE_ID = "pri_other";

    // 세 시각은 크기 비교용이다: EARLY < MIDDLE < LATE
    private static final Instant EARLY = Instant.parse("2026-09-24T02:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-09-24T02:30:00Z");
    private static final Instant LATE = Instant.parse("2026-09-24T03:00:00Z");

    // 기본 상태의 updated_at은 LATE, 웹훅 봉투 occurred_at 역할의 대체 기준은 EARLY
    private static final Instant UPDATED_AT = LATE;
    private static final Instant FALLBACK_AT = EARLY;

    private static final Instant PERIOD_ENDS_AT = Instant.parse("2026-10-24T00:00:00Z");
    private static final Instant NEXT_BILLED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant SCHEDULED_EFFECTIVE_AT = Instant.parse("2026-10-24T00:00:00Z");
    private static final Instant CANCELED_AT = Instant.parse("2026-09-30T00:00:00Z");
    private static final Duration GRACE = Duration.ofDays(3);

    @Mock
    private BillingSubscriptionRepository billingSubscriptionRepository;

    @Mock
    private PlanService planService;

    // ── 잠금 순서: 사용자 잠금 → findById(정확히 1회) ──

    @Test
    @DisplayName("사용자 행을 먼저 잠그고 그 뒤에야 구독 캐시를 읽는다 — findById는 정확히 1회"
            + " (잠그기 전에 읽으면 동시에 온 알림이 옛 사본으로 덮어쓴다)")
    void applyLocksUserBeforeReadingSubscriptionCacheExactlyOnce() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        InOrder inOrder = inOrder(planService, billingSubscriptionRepository);
        inOrder.verify(planService).lockForBillingUpdate(USER_ID);
        inOrder.verify(billingSubscriptionRepository).findById(SUBSCRIPTION_ID);
        inOrder.verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        // 잠근 뒤 다시 읽어도 JPA 1차 캐시가 같은 옛 객체를 돌려주므로, 읽기는 잠금 뒤 1회뿐이어야 한다
        verify(billingSubscriptionRepository, times(1)).findById(SUBSCRIPTION_ID);
    }

    @Test
    @DisplayName("해지 + 다른 구독 없음(강등) 분기도 잠근 뒤에 캐시를 읽고, 다른 구독 검사도 잠금 뒤다")
    void applyLocksUserBeforeAnyReadOnDowngradeBranch() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
        when(billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                eq(USER_ID), any(), eq(SUBSCRIPTION_ID))).thenReturn(false);

        ApplyResult result = applier.apply(state().status("canceled").build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        InOrder cacheRead = inOrder(planService, billingSubscriptionRepository);
        cacheRead.verify(planService).lockForBillingUpdate(USER_ID);
        cacheRead.verify(billingSubscriptionRepository).findById(SUBSCRIPTION_ID);
        InOrder otherSubscriptionRead = inOrder(planService, billingSubscriptionRepository);
        otherSubscriptionRead.verify(planService).lockForBillingUpdate(USER_ID);
        otherSubscriptionRead.verify(billingSubscriptionRepository)
                .existsByUserIdAndStatusInAndSubscriptionIdNot(eq(USER_ID), any(), eq(SUBSCRIPTION_ID));
        verify(billingSubscriptionRepository, times(1)).findById(SUBSCRIPTION_ID);
        verify(planService).downgradeToFree(USER_ID);
    }

    @Test
    @DisplayName("다른 구독이 살아 있어 강등하지 않는 해지 분기도 잠근 뒤에 읽고, 캐시는 갱신하며 강등은 하지 않는다")
    void applyLocksUserBeforeAnyReadWhenOtherLiveSubscriptionPreventsDowngrade() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
        when(billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                eq(USER_ID), any(), eq(SUBSCRIPTION_ID))).thenReturn(true);

        ApplyResult result = applier.apply(state().status("canceled").build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        InOrder cacheRead = inOrder(planService, billingSubscriptionRepository);
        cacheRead.verify(planService).lockForBillingUpdate(USER_ID);
        cacheRead.verify(billingSubscriptionRepository).findById(SUBSCRIPTION_ID);
        InOrder otherSubscriptionRead = inOrder(planService, billingSubscriptionRepository);
        otherSubscriptionRead.verify(planService).lockForBillingUpdate(USER_ID);
        otherSubscriptionRead.verify(billingSubscriptionRepository)
                .existsByUserIdAndStatusInAndSubscriptionIdNot(eq(USER_ID), any(), eq(SUBSCRIPTION_ID));
        verify(billingSubscriptionRepository, times(1)).findById(SUBSCRIPTION_ID);
        verify(planService, never()).downgradeToFree(any());
        verify(billingSubscriptionRepository).save(any(BillingSubscription.class));
    }

    // ── 사용자 결정: custom_data → 스칼라 projection → 없으면 잠그지 않고 UNMATCHED ──

    @Test
    @DisplayName("custom_data.user_id가 올바른 UUID면 그 사용자를 잠그고 반영한다 (캐시 소유자가 달라도 custom_data가 우선)")
    void applyUsesCustomDataUserIdWhenItIsAValidUuid() {
        PaddleSubscriptionApplier applier = applier();
        // 구현이 소유자 조회를 아예 하지 않을 수도 있어 lenient
        lenient().when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(owner(OTHER_USER_ID, PRICE_ID)));

        ApplyResult result = applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verify(planService).lockForBillingUpdate(USER_ID);
        verify(planService, never()).lockForBillingUpdate(OTHER_USER_ID);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-a-uuid"})
    @DisplayName("custom_data.user_id가 없거나 UUID가 아니면 캐시의 스칼라 projection에서 사용자를 찾는다")
    void applyFallsBackToScalarProjectionWhenCustomDataUserIdIsMissingOrInvalid(String customDataUserId) {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(owner(USER_ID, PRICE_ID)));
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(EARLY)));

        ApplyResult result = applier.apply(
                state().customDataUserId(customDataUserId).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verify(planService).lockForBillingUpdate(USER_ID);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        ArgumentCaptor<BillingSubscription> saved = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("사용자를 정할 수 없으면(custom_data도 캐시도 없음) → UNMATCHED, 잠그지도 읽지도 저장하지도 않는다")
    void applyReturnsUnmatchedWithoutLockingWhenNoUserCanBeResolved() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(
                state().customDataUserId(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNMATCHED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.userId()).isNull();
        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).findById(any());
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("잠금이 NotFoundException(파기된 사용자)이면 → UNMATCHED, 구독 캐시를 읽지도 저장하지도 않는다")
    void applyReturnsUnmatchedWhenLockReportsUserNotFound() {
        PaddleSubscriptionApplier applier = applier();
        doThrow(new NotFoundException("User not found.")).when(planService).lockForBillingUpdate(USER_ID);

        ApplyResult result = applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNMATCHED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verify(billingSubscriptionRepository, never()).findById(any());
        verify(billingSubscriptionRepository, never()).save(any());
        verify(planService, never()).activatePaid(any(), any());
        verify(planService, never()).downgradeToFree(any());
    }

    @Test
    @DisplayName("강등 중 PlanService가 NotFoundException을 던져도 → UNMATCHED, 구독 캐시는 저장하지 않는다")
    void applyReturnsUnmatchedWhenDowngradeReportsUserNotFound() {
        PaddleSubscriptionApplier applier = applier();
        doThrow(new NotFoundException("User not found.")).when(planService).downgradeToFree(USER_ID);

        ApplyResult result = applier.apply(state().status("canceled").build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNMATCHED);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    // ── 순서 기준: state.updatedAt 우선, 없으면 fallback, 둘 다 없으면 IGNORED ──

    @Test
    @DisplayName("저장하는 last_event_occurred_at은 fallback이 아니라 state.updatedAt이다")
    void applyStoresStateUpdatedAtAsLastEventOccurredAt() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        applier.apply(state().updatedAt(LATE).build(), EARLY, ApplySource.WEBHOOK);

        assertThat(savedSubscription().getLastEventOccurredAt()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("updatedAt이 없으면 fallback(봉투 occurred_at)을 기준으로 쓰고 그 값을 저장한다")
    void applyUsesFallbackOrderingTimeWhenUpdatedAtMissing() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(state().updatedAt(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(savedSubscription().getLastEventOccurredAt()).isEqualTo(FALLBACK_AT);
    }

    @Test
    @DisplayName("캐시와 비교하는 것도 updatedAt이다 — fallback이 캐시보다 오래돼도 updatedAt이 더 새로우면 반영한다")
    void applyJudgesByUpdatedAtWhenFallbackIsOlderThanCache() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(MIDDLE)));

        ApplyResult result = applier.apply(state().updatedAt(LATE).build(), EARLY, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        assertThat(savedSubscription().getLastEventOccurredAt()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("fallback(봉투 occurred_at)이 캐시보다 새로워도 updatedAt이 더 오래됐으면 STALE이다 (봉투 시각만 보면 옛 상태가 이긴다)")
    void applyJudgesByUpdatedAtWhenFallbackIsNewerThanCache() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(MIDDLE)));

        ApplyResult result = applier.apply(state().updatedAt(EARLY).build(), LATE, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.STALE);
        verifyNothingApplied();
    }

    @ParameterizedTest
    @EnumSource(ApplySource.class)
    @DisplayName("updatedAt도 fallback도 없으면 순서를 정할 수 없어 → IGNORED, 플랜·캐시를 건드리지 않는다")
    void applyIgnoresWhenNeitherUpdatedAtNorFallbackIsAvailable(ApplySource source) {
        PaddleSubscriptionApplier applier = applier();

        ApplyResult result = applier.apply(state().updatedAt(null).build(), null, source);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        verifyNothingApplied();
    }

    // ── 출처별 비교 규칙 ──

    @Test
    @DisplayName("WEBHOOK: 캐시의 시각과 기준 시각이 같으면 반영한다")
    void applyFromWebhookAppliesWhenCachedTimeEqualsOrderingTime() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(UPDATED_AT)));

        ApplyResult result = applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        verify(billingSubscriptionRepository).save(any(BillingSubscription.class));
    }

    @Test
    @DisplayName("WEBHOOK: 캐시의 시각이 기준보다 엄격히 이후면 STALE, 플랜·캐시를 건드리지 않는다")
    void applyFromWebhookReturnsStaleWhenCachedTimeIsStrictlyLater() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(UPDATED_AT.plusSeconds(1))));

        ApplyResult result = applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.STALE);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verifyNothingApplied();
    }

    @Test
    @DisplayName("WEBHOOK: 캐시의 시각이 기준보다 이전이면 반영한다 (비교 방향 확인)")
    void applyFromWebhookAppliesWhenCachedTimeIsEarlier() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(UPDATED_AT.minusSeconds(1))));

        ApplyResult result = applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(billingSubscriptionRepository).save(any(BillingSubscription.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "canceled"})
    @DisplayName("API: 기준 시각이 캐시와 같으면 UNCHANGED — 반영·강등·저장 없이 끝난다 (매시간 전부 다시 쓰지 않기 위해)")
    void applyFromApiReturnsUnchangedWhenTimesAreEqual(String status) {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(UPDATED_AT)));

        ApplyResult result = applier.apply(state().status(status).build(), null, ApplySource.API);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNCHANGED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verifyNothingApplied();
    }

    @Test
    @DisplayName("API: 기준 시각이 캐시보다 이전이면 STALE, 플랜·캐시를 건드리지 않는다")
    void applyFromApiReturnsStaleWhenOrderingTimeIsOlderThanCache() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(UPDATED_AT.plusSeconds(1))));

        ApplyResult result = applier.apply(state().build(), null, ApplySource.API);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.STALE);
        verifyNothingApplied();
    }

    @Test
    @DisplayName("API: 기준 시각이 캐시보다 엄격히 이후면 반영한다")
    void applyFromApiAppliesWhenOrderingTimeIsStrictlyNewerThanCache() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(UPDATED_AT.minusSeconds(1))));

        ApplyResult result = applier.apply(state().build(), null, ApplySource.API);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        assertThat(savedSubscription().getLastEventOccurredAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    @DisplayName("API: 캐시에 없던 구독이면 반영한다 (생성 알림을 잃은 구독 복구)")
    void applyFromApiAppliesWhenSubscriptionIsNotCachedYet() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(state().build(), null, ApplySource.API);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        assertThat(savedSubscription().getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
    }

    // ── 걸러내는 조건: id/customer_id, 가격 ──

    @Test
    @DisplayName("data.id가 없으면 → IGNORED, 아무 협력자도 건드리지 않는다")
    void applyIgnoresWhenSubscriptionIdMissing() {
        PaddleSubscriptionApplier applier = applier();

        ApplyResult result = applier.apply(state().subscriptionId(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.subscriptionId()).isNull();
        assertThat(result.userId()).isNull();
        verifyNoInteractions(planService, billingSubscriptionRepository);
    }

    @Test
    @DisplayName("customer_id가 없으면(캐시 NOT NULL 컬럼) → IGNORED, 아무 협력자도 건드리지 않는다")
    void applyIgnoresWhenCustomerIdMissing() {
        PaddleSubscriptionApplier applier = applier();

        ApplyResult result = applier.apply(state().customerId(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        verifyNoInteractions(planService, billingSubscriptionRepository);
    }

    @Test
    @DisplayName("pro-price-id가 설정돼 있고 priceIds에 그 가격이 없으면 → IGNORED, 잠그지도 읽지도 않는다")
    void applyIgnoresWithoutLockingWhenNoItemMatchesProPrice() {
        PaddleSubscriptionApplier applier = applier(PRICE_ID);

        ApplyResult result = applier.apply(
                state().priceIds(List.of(OTHER_PRICE_ID)).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.userId()).isNull();
        verifyNoInteractions(planService, billingSubscriptionRepository);
    }

    @Test
    @DisplayName("pro-price-id가 비어 있으면 가격을 거르지 않고 첫 item의 가격을 캐시에 저장한다")
    void applyStoresFirstItemPriceWhenProPriceIdNotConfigured() {
        PaddleSubscriptionApplier applier = applier("");
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(
                state().priceIds(List.of(OTHER_PRICE_ID, PRICE_ID)).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(savedSubscription().getPriceId()).isEqualTo(OTHER_PRICE_ID);
    }

    @Test
    @DisplayName("items가 없고(priceIds null) 캐시의 가격이 Pro가 아니면 → IGNORED, 잠그지도 읽지도 않는다")
    void applyIgnoresWithoutLockingWhenItemsMissingAndCachedPriceIsNotProPrice() {
        PaddleSubscriptionApplier applier = applier(PRICE_ID);
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(owner(USER_ID, OTHER_PRICE_ID)));

        ApplyResult result = applier.apply(state().priceIds(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.userId()).isNull();
        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).findById(any());
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("items가 없고(priceIds null) 캐시에 구독도 없으면 → IGNORED, 잠그지도 저장하지도 않는다")
    void applyIgnoresWhenItemsMissingAndSubscriptionNotCached() {
        PaddleSubscriptionApplier applier = applier(PRICE_ID);
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(state().priceIds(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("items가 없거나(null) 빈 리스트면 projection의 캐시 가격으로 가격을 검사하고, 그 가격을 캐시에 유지한다")
    void applyUsesProjectionPriceIdWhenItemsMissingOrEmpty(boolean emptyList) {
        PaddleSubscriptionApplier applier = applier(PRICE_ID);
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(owner(USER_ID, PRICE_ID)));
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(cachedSubscription(EARLY)));

        ApplyResult result = applier.apply(
                state().priceIds(emptyList ? List.of() : null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(savedSubscription().getPriceId()).isEqualTo(PRICE_ID);
        verify(planService).lockForBillingUpdate(USER_ID);
    }

    // ── 상태별 플랜 반영 ──

    @ParameterizedTest
    @ValueSource(strings = {"active", "trialing", "past_due"})
    @DisplayName("active·trialing·past_due는 activatePaid(기간 끝 + grace)를 부르고 상태를 캐시에 저장한다")
    void applyActivatesPaidForActiveLikeStatuses(String status) {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        ApplyResult result = applier.apply(state().status(status).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        verify(planService, never()).downgradeToFree(any());
        assertThat(savedSubscription().getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"canceled", "paused"})
    @DisplayName("canceled·paused는 다른 살아 있는 구독(active·trialing·past_due)이 없을 때만 downgradeToFree를 부른다")
    void applyDowngradesForInactiveLikeStatusesWhenNoOtherLiveSubscription(String status) {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
        when(billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                eq(USER_ID), any(), eq(SUBSCRIPTION_ID))).thenReturn(false);

        ApplyResult result = applier.apply(
                state().status(status).canceledAt(CANCELED_AT).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        verify(planService).downgradeToFree(USER_ID);
        verify(planService, never()).activatePaid(any(), any());
        BillingSubscription saved = savedSubscription();
        assertThat(saved.getStatus()).isEqualTo(status);
        assertThat(saved.getCanceledAt()).isEqualTo(CANCELED_AT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> liveStatuses = ArgumentCaptor.forClass(List.class);
        verify(billingSubscriptionRepository)
                .existsByUserIdAndStatusInAndSubscriptionIdNot(eq(USER_ID), liveStatuses.capture(), eq(SUBSCRIPTION_ID));
        assertThat(liveStatuses.getValue()).containsExactlyInAnyOrder("active", "trialing", "past_due");
    }

    @Test
    @DisplayName("알 수 없는 status(예: frozen) → IGNORED, 플랜 반영도 캐시 저장도 하지 않는다")
    void applyIgnoresUnknownStatusWithoutSavingOrChangingPlan() {
        PaddleSubscriptionApplier applier = applier();

        ApplyResult result = applier.apply(state().status("frozen").build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        verifyNothingApplied();
    }

    @Test
    @DisplayName("status가 없으면(null) 알 수 없는 상태와 같이 IGNORED다")
    void applyIgnoresMissingStatus() {
        PaddleSubscriptionApplier applier = applier();

        ApplyResult result = applier.apply(state().status(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        verifyNothingApplied();
    }

    @Test
    @DisplayName("만료는 기간 끝을 next_billed_at보다 먼저 쓴다 (둘 다 있으면 기간 끝 + grace)")
    void applyPrefersPeriodEndOverNextBilledAtForExpiry() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        applier.apply(
                state().currentPeriodEndsAt(PERIOD_ENDS_AT).nextBilledAt(NEXT_BILLED_AT).build(),
                FALLBACK_AT, ApplySource.WEBHOOK);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
    }

    @Test
    @DisplayName("기간 끝이 없고 next_billed_at만 있으면 만료는 next_billed_at + grace지만, 캐시의 기간 끝은 null로 둔다")
    void applyExpiresAtNextBilledAtButKeepsCachedPeriodEndNullWhenPeriodMissing() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        applier.apply(
                state().currentPeriodEndsAt(null).nextBilledAt(NEXT_BILLED_AT).build(),
                FALLBACK_AT, ApplySource.WEBHOOK);

        verify(planService).activatePaid(USER_ID, NEXT_BILLED_AT.plus(GRACE));
        assertThat(savedSubscription().getCurrentPeriodEndsAt()).isNull();
    }

    @Test
    @DisplayName("활성 상태인데 만료를 정할 수 없으면(기간 끝·next_billed_at 모두 없음) → IGNORED, 플랜·캐시를 건드리지 않는다")
    void applyIgnoresActiveStateWhenNoExpiryCanBeDetermined() {
        PaddleSubscriptionApplier applier = applier();

        ApplyResult result = applier.apply(
                state().currentPeriodEndsAt(null).nextBilledAt(null).build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.IGNORED);
        assertThat(result.userId()).isEqualTo(USER_ID);
        verifyNothingApplied();
    }

    @Test
    @DisplayName("캐시 행에는 상태의 모든 필드(사용자·고객·가격·기간 끝·예약 변경·해지 시각·순서 기준)를 그대로 저장한다")
    void applySavesEveryStateFieldIntoCacheRow() {
        PaddleSubscriptionApplier applier = applier();
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        applier.apply(
                state().scheduledChangeAction("cancel").scheduledChangeEffectiveAt(SCHEDULED_EFFECTIVE_AT)
                        .canceledAt(CANCELED_AT).build(),
                FALLBACK_AT, ApplySource.WEBHOOK);

        BillingSubscription saved = savedSubscription();
        assertThat(saved.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getPriceId()).isEqualTo(PRICE_ID);
        assertThat(saved.getCurrentPeriodEndsAt()).isEqualTo(PERIOD_ENDS_AT);
        assertThat(saved.getScheduledChangeAction()).isEqualTo("cancel");
        assertThat(saved.getScheduledChangeEffectiveAt()).isEqualTo(SCHEDULED_EFFECTIVE_AT);
        assertThat(saved.getCanceledAt()).isEqualTo(CANCELED_AT);
        assertThat(saved.getLastEventOccurredAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    @DisplayName("캐시 행을 다시 쓸 때 이미 보낸 결제 안내 주기(renewalNoticePeriodEnd)는 유지한다")
    void applyKeepsRenewalNoticePeriodEndWhenOverwritingCacheRow() {
        PaddleSubscriptionApplier applier = applier();
        BillingSubscription cached = cachedSubscription(EARLY);
        Instant noticePeriodEnd = PERIOD_ENDS_AT.minusSeconds(3600);
        cached.recordRenewalNoticePeriodEnd(noticePeriodEnd);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(cached));

        applier.apply(state().build(), FALLBACK_AT, ApplySource.WEBHOOK);

        assertThat(savedSubscription().getRenewalNoticePeriodEnd()).isEqualTo(noticePeriodEnd);
    }

    // 아무것도 반영하지 않았음을 확정 — 잠금 호출 자체는 허용한다(잠근 뒤에야 비교·상태 판정을 하므로)
    private void verifyNothingApplied() {
        verify(planService, never()).activatePaid(any(), any());
        verify(planService, never()).downgradeToFree(any());
        verify(billingSubscriptionRepository, never()).save(any());
    }

    private BillingSubscription savedSubscription() {
        ArgumentCaptor<BillingSubscription> captor = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(captor.capture());
        return captor.getValue();
    }

    private PaddleSubscriptionApplier applier() {
        return applier("");
    }

    private PaddleSubscriptionApplier applier(String proPriceId) {
        return new PaddleSubscriptionApplier(
                billingSubscriptionRepository,
                planService,
                new PaddleProperties("test-secret", Duration.ofSeconds(5), proPriceId, null, "sandbox", null),
                new BillingProperties(GRACE));
    }

    private BillingSubscription cachedSubscription(Instant lastEventOccurredAt) {
        return new BillingSubscription(
                SUBSCRIPTION_ID,
                USER_ID,
                CUSTOMER_ID,
                "active",
                PRICE_ID,
                PERIOD_ENDS_AT,
                null,
                null,
                null,
                lastEventOccurredAt
        );
    }

    // 스칼라 projection — 엔티티가 아니라서 영속성 컨텍스트에 남지 않는다
    private BillingSubscriptionOwner owner(UUID userId, String priceId) {
        return new BillingSubscriptionOwner() {
            @Override
            public UUID getUserId() {
                return userId;
            }

            @Override
            public String getPriceId() {
                return priceId;
            }
        };
    }

    private StateBuilder state() {
        return new StateBuilder();
    }

    // 기본값은 "custom_data로 사용자를 아는 active 구독, updated_at = LATE" — 테스트가 바꿀 필드만 덮어쓴다
    private static final class StateBuilder {
        private String subscriptionId = SUBSCRIPTION_ID;
        private String status = "active";
        private String customerId = CUSTOMER_ID;
        private List<String> priceIds = List.of(PRICE_ID);
        private String customDataUserId = USER_ID.toString();
        private String scheduledChangeAction;
        private Instant scheduledChangeEffectiveAt;
        private Instant canceledAt;
        private Instant currentPeriodEndsAt = PERIOD_ENDS_AT;
        private Instant nextBilledAt = PERIOD_ENDS_AT;
        private Instant updatedAt = UPDATED_AT;

        StateBuilder subscriptionId(String value) {
            this.subscriptionId = value;
            return this;
        }

        StateBuilder status(String value) {
            this.status = value;
            return this;
        }

        StateBuilder customerId(String value) {
            this.customerId = value;
            return this;
        }

        StateBuilder priceIds(List<String> value) {
            this.priceIds = value;
            return this;
        }

        StateBuilder customDataUserId(String value) {
            this.customDataUserId = value;
            return this;
        }

        StateBuilder scheduledChangeAction(String value) {
            this.scheduledChangeAction = value;
            return this;
        }

        StateBuilder scheduledChangeEffectiveAt(Instant value) {
            this.scheduledChangeEffectiveAt = value;
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
                    subscriptionId, status, customerId, priceIds, customDataUserId,
                    scheduledChangeAction, scheduledChangeEffectiveAt, canceledAt,
                    currentPeriodEndsAt, nextBilledAt, updatedAt);
        }
    }
}
