package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.service.PlanService;
import com.history.backend.billing.BillingProperties;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.domain.BillingEventOutcome;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingEventRepository;
import com.history.backend.billing.repository.BillingSubscriptionOwner;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyOutcome;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaddleWebhookService: Paddle 결제 알림 수신·검증·멱등·구독 동기화")
class PaddleWebhookServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUBSCRIPTION_ID = "sub_01m38gzr9f7v4h6k2n8p1q3s5t";
    private static final String CUSTOMER_ID = "ctm_01m38h0a3b5c7d9e1f2g4h6j8k";
    private static final String PRICE_ID = "pri_01m38fshra9skx2brd7azsb221";
    private static final String EVENT_ID = "evt_01";
    private static final String SIGNATURE_HEADER = "ts=1758682800;h1=deadbeef";
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T03:00:00Z");
    private static final Instant PERIOD_ENDS_AT = Instant.parse("2026-10-24T00:00:00Z");
    private static final Duration GRACE = Duration.ofDays(3);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private PaddleSignatureVerifier verifier;

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private BillingSubscriptionRepository billingSubscriptionRepository;

    @Mock
    private PlanService planService;

    @Test
    @DisplayName("서명 검증 실패 → UnauthorizedException, 원장 claim 미호출")
    void handleThrowsUnauthorizedWhenSignatureInvalid() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.created", baseSubscriptionData());
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(false);

        assertThatThrownBy(() -> service.handle(SIGNATURE_HEADER, body))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(billingEventRepository, billingSubscriptionRepository, planService);
    }

    @Test
    @DisplayName("JSON이 깨졌으면 → BadRequestException, claim 미호출")
    void handleThrowsBadRequestWhenBodyIsMalformedJson() {
        PaddleWebhookService service = service();
        String body = "not-json-at-all";
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(true);

        assertThatThrownBy(() -> service.handle(SIGNATURE_HEADER, body))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(billingEventRepository, billingSubscriptionRepository, planService);
    }

    @Test
    @DisplayName("봉투 필드(event_id 등)가 없으면 → BadRequestException, claim 미호출")
    void handleThrowsBadRequestWhenEnvelopeFieldIsMissing() {
        PaddleWebhookService service = service();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_type", "subscription.created");
        envelope.put("occurred_at", OCCURRED_AT.toString());
        envelope.put("data", baseSubscriptionData());
        String body = writeJson(envelope);
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(true);

        assertThatThrownBy(() -> service.handle(SIGNATURE_HEADER, body))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(billingEventRepository, billingSubscriptionRepository, planService);
    }

    @Test
    @DisplayName("중복 알림(claim 0행) → 아무 처리도 하지 않는다")
    void handleDoesNothingWhenEventIsDuplicate() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.created", baseSubscriptionData());
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(true);
        when(billingEventRepository.tryClaim(
                eq(EVENT_ID), eq("subscription.created"), eq(OCCURRED_AT), anyString(), any(Instant.class)))
                .thenReturn(0);

        assertThatCode(() -> service.handle(SIGNATURE_HEADER, body)).doesNotThrowAnyException();

        verify(billingEventRepository, never()).updateOutcome(anyString(), anyString(), any(), any());
        verifyNoInteractions(billingSubscriptionRepository, planService);
    }

    @Test
    @DisplayName("subscription.* 이외 이벤트 → IGNORED로 기록만, 플랜 호출 없음")
    void handleIgnoresNonSubscriptionEventType() {
        PaddleWebhookService service = service();
        String body = envelope("transaction.completed", baseSubscriptionData());
        stubValidRequest(body, "transaction.completed", 1);

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository).updateOutcome(EVENT_ID, BillingEventOutcome.IGNORED.name(), null, null);
        verifyNoInteractions(planService, billingSubscriptionRepository);
    }

    @Test
    @DisplayName("proPriceId가 설정돼 있고 items에 그 가격이 없으면 → IGNORED, 플랜 호출 없음")
    void handleIgnoresWhenProPriceIdConfiguredAndMismatched() {
        PaddleWebhookService service = service(PRICE_ID);
        Map<String, Object> data = baseSubscriptionData();
        data.put("items", List.of(Map.of("price", Map.of("id", "pri_other", "product_id", "pro_other"))));
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.IGNORED.name(), SUBSCRIPTION_ID, null);
        verifyNoInteractions(planService, billingSubscriptionRepository);
    }

    @Test
    @DisplayName("items가 둘 이상이고 두 번째가 Pro 가격이면 → 처리된다(activatePaid 호출),"
            + " 캐시에는 매칭된 Pro 가격이 저장된다 (지금은 items[0]만 봐서 실패해야 한다)")
    void handleProcessesWhenProPriceIsNotFirstItem() {
        PaddleWebhookService service = service(PRICE_ID);
        Map<String, Object> data = baseSubscriptionData();
        data.put("items", List.of(
                Map.of("price", Map.of("id", "pri_other", "product_id", "pro_other")),
                Map.of("price", Map.of("id", PRICE_ID, "product_id", "pro_01m38fshezrep26abtbj1h1rde"))
        ));
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        // 결함이 있으면 가격 불일치로 findById 전에 IGNORED로 끝나 이 스텁은 안 쓰인다 — lenient로 둔다.
        lenient().when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        ArgumentCaptor<BillingSubscription> captor = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getPriceId()).isEqualTo(PRICE_ID);
    }

    @Test
    @DisplayName("items가 없고 기존 캐시의 priceId가 Pro면 canceled 알림도 처리된다(downgradeToFree 호출),"
            + " 캐시 priceId는 유지된다 (지금은 items[0]만 봐서 실패해야 한다)")
    void handleProcessesCanceledWithoutItemsWhenCachedPriceIdMatchesProPrice() {
        PaddleWebhookService service = service(PRICE_ID);
        Map<String, Object> data = baseSubscriptionData();
        data.remove("items");
        data.put("status", "canceled");
        String body = envelope("subscription.canceled", data);
        stubValidRequest(body, "subscription.canceled", 1);
        // existingSubscription()은 PRICE_ID(=proPriceId)를 캐시 priceId로 갖는다 — items가 없을 때 가격은
        // 엔티티가 아니라 스칼라 projection으로 읽는다(잠금 전에 엔티티를 영속성 컨텍스트에 올리지 않기 위해)
        lenient().when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(owner(USER_ID, PRICE_ID)));
        lenient().when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(existingSubscription(OCCURRED_AT.minusSeconds(3600))));
        lenient().when(billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                eq(USER_ID), any(), eq(SUBSCRIPTION_ID))).thenReturn(false);

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).downgradeToFree(USER_ID);
        ArgumentCaptor<BillingSubscription> captor = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getPriceId()).isEqualTo(PRICE_ID);
    }

    @Test
    @DisplayName("items가 없고 기존 캐시도 없으면 → IGNORED, 플랜 호출·구독 저장 없음")
    void handleIgnoresWhenItemsMissingAndNoCachedSubscription() {
        PaddleWebhookService service = service(PRICE_ID);
        Map<String, Object> data = baseSubscriptionData();
        data.remove("items");
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        // items가 없으면 가격은 스칼라 projection으로 읽는다 — 캐시가 없으면 가격을 알 수 없어 IGNORED
        lenient().when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.IGNORED.name(), SUBSCRIPTION_ID, null);
        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("subscription.* 이벤트에 data.id가 없으면 → IGNORED, findById·플랜 호출 없이 예외 없이 끝난다"
            + " (findById(null)은 실제 JpaRepository에서 IllegalArgumentException — 500·무한 재시도 방지)")
    void handleIgnoresSubscriptionEventWithoutSubscriptionId() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.remove("id");
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        // 실제 JpaRepository.findById(null)은 IllegalArgumentException을 던진다 — 이 스텁으로 그 동작을
        // 재현한다. 고쳐진 구현은 findById를 아예 부르지 않아야 하므로 lenient로 둔다(부르지 않으면
        // UnnecessaryStubbingException 없이 통과해야 한다).
        lenient().when(billingSubscriptionRepository.findById(isNull()))
                .thenThrow(new IllegalArgumentException("The given id must not be null!"));

        assertThatCode(() -> service.handle(SIGNATURE_HEADER, body)).doesNotThrowAnyException();

        verify(billingEventRepository).updateOutcome(EVENT_ID, BillingEventOutcome.IGNORED.name(), null, null);
        verify(billingSubscriptionRepository, never()).findById(any());
        verifyNoInteractions(planService);
    }

    @Test
    @DisplayName("subscription.* 이벤트에 customer_id가 없으면 → IGNORED로 기록하고 플랜 호출·구독 저장 없이"
            + " 예외 없이 끝난다 (customer_id는 billing_subscriptions에서 NOT NULL — 지금은 save를 호출해"
            + " 제약 위반으로 500·무한 재시도가 되므로 실패해야 한다)")
    void handleIgnoresSubscriptionEventWithoutCustomerId() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("customer_id", null);
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        // data.id 가드와 같은 급으로 findById보다 먼저 끝나야 하므로 이 스텁은 안 쓰일 수 있다 — lenient.
        lenient().when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.handle(SIGNATURE_HEADER, body)).doesNotThrowAnyException();

        verify(billingEventRepository)
                .updateOutcome(eq(EVENT_ID), eq(BillingEventOutcome.IGNORED.name()), any(), any());
        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("custom_data.user_id도 없고 기존 구독도 없으면 → UNMATCHED, 예외 없이 종료")
    void handleRecordsUnmatchedWhenNoUserIdResolvable() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("custom_data", null);
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        // custom_data가 없으면 스칼라 projection으로 사용자를 찾는다 — 그것도 없으면 잠그지 않고 UNMATCHED
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.handle(SIGNATURE_HEADER, body)).doesNotThrowAnyException();

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.UNMATCHED.name(), SUBSCRIPTION_ID, null);
        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("custom_data.user_id가 없으면 기존 구독(같은 subscription_id)의 user_id로 대체한다")
    void handleFallsBackToExistingSubscriptionUserIdWhenCustomDataMissing() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("custom_data", null);
        String body = envelope("subscription.updated", data);
        stubValidRequest(body, "subscription.updated", 1);
        // 사용자는 스칼라 projection으로 찾고, 순서 비교용 엔티티는 잠금 뒤 findById로 읽는다
        when(billingSubscriptionRepository.findOwnerBySubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(owner(USER_ID, PRICE_ID)));
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(existingSubscription(OCCURRED_AT.minusSeconds(3600))));

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
    }

    @Test
    @DisplayName("만료 시각 = current_billing_period.ends_at + grace를 정확히 계산해 activatePaid를 호출한다")
    void handleAppliesActivatePaidWithExactExpiry() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.created", baseSubscriptionData());
        stubValidRequest(body, "subscription.created", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(planService).activatePaid(eq(USER_ID), expiryCaptor.capture());
        assertThat(expiryCaptor.getValue()).isEqualTo(PERIOD_ENDS_AT.plus(GRACE));
        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    @DisplayName("current_billing_period가 null이면 next_billed_at + grace를 쓴다")
    void handleUsesNextBilledAtWhenCurrentPeriodMissing() {
        PaddleWebhookService service = service();
        Instant nextBilledAt = Instant.parse("2026-11-01T00:00:00Z");
        Map<String, Object> data = baseSubscriptionData();
        data.put("current_billing_period", null);
        data.put("next_billed_at", nextBilledAt.toString());
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, nextBilledAt.plus(GRACE));
    }

    @Test
    @DisplayName("period ends_at·next_billed_at이 둘 다 없으면 → IGNORED, 플랜 호출 없음")
    void handleIgnoresActiveWhenNoExpirySourceAvailable() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("current_billing_period", null);
        data.put("next_billed_at", null);
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.IGNORED.name(), SUBSCRIPTION_ID, USER_ID);
        // 잠근 뒤에 만료를 판정하므로 잠금 호출은 허용된다 — 플랜 변경만 없어야 한다
        verify(planService, never()).activatePaid(any(), any());
        verify(planService, never()).downgradeToFree(any());
    }

    @Test
    @DisplayName("past_due 상태도 activatePaid를 호출해 유예 기간 동안 PAID를 유지한다 (D1)")
    void handleActivatesPaidForPastDueStatus() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("status", "past_due");
        String body = envelope("subscription.updated", data);
        stubValidRequest(body, "subscription.updated", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
    }

    @Test
    @DisplayName("저장된 lastEventOccurredAt보다 오래된 알림 → STALE, 플랜 호출·구독 덮어쓰기 없음")
    void handleSkipsStaleEventOlderThanStoredOccurredAt() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.updated", baseSubscriptionData());
        stubValidRequest(body, "subscription.updated", 1);
        // 저장된 lastEventOccurredAt이 이번 occurred_at보다 미래(=이번 알림이 더 오래됨) → stale
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(existingSubscription(OCCURRED_AT.plusSeconds(3600))));

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.STALE.name(), SUBSCRIPTION_ID, USER_ID);
        // 잠근 뒤에 비교하므로 잠금 호출은 허용된다 — 플랜 변경만 없어야 한다
        verify(planService, never()).activatePaid(any(), any());
        verify(planService, never()).downgradeToFree(any());
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("저장된 lastEventOccurredAt보다 최신인 알림은 stale이 아니라 정상 처리된다 (비교 방향 확인)")
    void handleProcessesEventNewerThanStoredOccurredAt() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.updated", baseSubscriptionData());
        stubValidRequest(body, "subscription.updated", 1);
        // 저장된 lastEventOccurredAt이 이번 occurred_at보다 과거(=이번 알림이 더 최신) → 정상 처리
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(existingSubscription(OCCURRED_AT.minusSeconds(3600))));

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
        verify(billingSubscriptionRepository).save(any(BillingSubscription.class));
    }

    @Test
    @DisplayName("구독 캐시를 덮어쓸 때 이미 보낸 결제 안내 주기는 유지한다")
    void handleKeepsRenewalNoticePeriodEndWhenUpdatingSubscription() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.updated", baseSubscriptionData());
        stubValidRequest(body, "subscription.updated", 1);
        BillingSubscription existing = existingSubscription(OCCURRED_AT.minusSeconds(60));
        Instant noticePeriodEnd = PERIOD_ENDS_AT.minusSeconds(3600);
        existing.recordRenewalNoticePeriodEnd(noticePeriodEnd);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(existing));

        service.handle(SIGNATURE_HEADER, body);

        ArgumentCaptor<BillingSubscription> saved = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getRenewalNoticePeriodEnd()).isEqualTo(noticePeriodEnd);
    }

    @Test
    @DisplayName("canceled + 다른 살아 있는 구독 없음 → downgradeToFree 호출")
    void handleDowngradesToFreeWhenCanceledAndNoOtherActiveSubscription() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("status", "canceled");
        String body = envelope("subscription.canceled", data);
        stubValidRequest(body, "subscription.canceled", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
        when(billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                eq(USER_ID), any(), eq(SUBSCRIPTION_ID))).thenReturn(false);

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).downgradeToFree(USER_ID);
        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    @DisplayName("canceled여도 같은 사용자의 다른 살아 있는 구독이 있으면 downgradeToFree를 호출하지 않는다")
    void handleKeepsPaidWhenCanceledButOtherActiveSubscriptionExists() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("status", "canceled");
        String body = envelope("subscription.canceled", data);
        stubValidRequest(body, "subscription.canceled", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
        when(billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                eq(USER_ID), any(), eq(SUBSCRIPTION_ID))).thenReturn(true);

        service.handle(SIGNATURE_HEADER, body);

        verify(planService, never()).downgradeToFree(any());
        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    @DisplayName("알 수 없는 status(예: frozen) → IGNORED로만 기록, 플랜 호출·구독 저장 없음"
            + " (파기된 사용자면 save가 user_id FK 위반으로 500·무한 재시도가 되므로, 구독 저장을 하고"
            + " APPLIED로 기록하는 지금 코드는 실패해야 한다)")
    void handleIgnoresUnknownStatusWithoutTouchingPlanOrSubscriptionCache() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("status", "frozen");
        String body = envelope("subscription.updated", data);
        stubValidRequest(body, "subscription.updated", 1);
        // 결함 수정으로 사용자 조회보다 먼저 끝나게 바뀔 수도 있어 이 스텁은 안 쓰일 수 있다 — lenient.
        lenient().when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository)
                .updateOutcome(eq(EVENT_ID), eq(BillingEventOutcome.IGNORED.name()), any(), any());
        // 잠근 뒤에 상태를 판정하므로 잠금 호출은 허용된다 — 플랜 변경만 없어야 한다
        verify(planService, never()).activatePaid(any(), any());
        verify(planService, never()).downgradeToFree(any());
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("해지 예약(scheduled_change)이 있어도 status가 active면 activatePaid를 호출하고 예약 필드를 기록한다")
    void handleAppliesScheduledChangeWhileKeepingPaidStatus() {
        PaddleWebhookService service = service();
        Instant effectiveAt = Instant.parse("2026-10-24T00:00:00Z");
        Map<String, Object> data = baseSubscriptionData();
        data.put("scheduled_change", Map.of(
                "action", "cancel",
                "effective_at", effectiveAt.toString()));
        String body = envelope("subscription.updated", data);
        stubValidRequest(body, "subscription.updated", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        ArgumentCaptor<BillingSubscription> captor = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getScheduledChangeAction()).isEqualTo("cancel");
        assertThat(captor.getValue().getScheduledChangeEffectiveAt()).isEqualTo(effectiveAt);
    }

    @Test
    @DisplayName("같은 알림이 재전송돼도(두 번째 claim 0) activatePaid는 한 번만 호출된다")
    void handleAppliesOnlyOnceWhenSameNotificationRetried() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.created", baseSubscriptionData());
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(true);
        when(billingEventRepository.tryClaim(
                eq(EVENT_ID), eq("subscription.created"), eq(OCCURRED_AT), anyString(), any(Instant.class)))
                .thenReturn(1)
                .thenReturn(0);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        service.handle(SIGNATURE_HEADER, body);
        service.handle(SIGNATURE_HEADER, body);

        verify(planService, times(1)).activatePaid(eq(USER_ID), any());
    }

    @Test
    @DisplayName("PlanService가 NotFoundException을 던지면(사용자 파기됨) → UNMATCHED로 기록, 예외 전파 없음, 구독 미저장")
    void handleRecordsUnmatchedWhenPlanServiceReportsUserNotFound() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.created", baseSubscriptionData());
        stubValidRequest(body, "subscription.created", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new NotFoundException("User not found."))
                .when(planService).activatePaid(eq(USER_ID), any());

        assertThatCode(() -> service.handle(SIGNATURE_HEADER, body)).doesNotThrowAnyException();

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.UNMATCHED.name(), SUBSCRIPTION_ID, USER_ID);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    private void stubValidRequest(String body, String eventType, int claimResult) {
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(true);
        when(billingEventRepository.tryClaim(
                eq(EVENT_ID), eq(eventType), eq(OCCURRED_AT), anyString(), any(Instant.class)))
                .thenReturn(claimResult);
    }

    private PaddleWebhookService service() {
        return service("");
    }

    // ── 파서·applier 위임 (반영 로직은 PaddleSubscriptionApplierTest가 다룬다) ──

    @Test
    @DisplayName("파싱한 상태를 applier에 넘긴다 — 출처는 WEBHOOK, 순서 대체 기준은 봉투 occurred_at,"
            + " 기록할 subscriptionId·userId는 applier 결과를 따른다")
    void handleDelegatesParsedStateToApplierAsWebhookSource() {
        PaddleSubscriptionApplier applier = mock(PaddleSubscriptionApplier.class);
        PaddleWebhookService service = new PaddleWebhookService(
                verifier, billingEventRepository, new PaddleSubscriptionParser(), applier, JSON);
        String body = envelope("subscription.updated", baseSubscriptionData());
        stubValidRequest(body, "subscription.updated", 1);
        when(applier.apply(any(PaddleSubscriptionState.class), eq(OCCURRED_AT), eq(ApplySource.WEBHOOK)))
                .thenReturn(new ApplyResult(ApplyOutcome.APPLIED, SUBSCRIPTION_ID, USER_ID));

        service.handle(SIGNATURE_HEADER, body);

        ArgumentCaptor<PaddleSubscriptionState> state = ArgumentCaptor.forClass(PaddleSubscriptionState.class);
        verify(applier).apply(state.capture(), eq(OCCURRED_AT), eq(ApplySource.WEBHOOK));
        assertThat(state.getValue().subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(state.getValue().status()).isEqualTo("active");
        assertThat(state.getValue().customDataUserId()).isEqualTo(USER_ID.toString());
        assertThat(state.getValue().priceIds()).containsExactly(PRICE_ID);
        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "APPLIED,APPLIED",
            "STALE,STALE",
            "UNCHANGED,STALE",
            "IGNORED,IGNORED",
            "UNMATCHED,UNMATCHED"
    })
    @DisplayName("applier의 결과를 billing_events outcome으로 옮긴다 (웹훅에서 나올 수 없는 UNCHANGED는 STALE로 둔다)")
    void handleRecordsApplierOutcomeAsBillingEventOutcome(ApplyOutcome applied, BillingEventOutcome recorded) {
        PaddleSubscriptionApplier applier = mock(PaddleSubscriptionApplier.class);
        PaddleWebhookService service = new PaddleWebhookService(
                verifier, billingEventRepository, new PaddleSubscriptionParser(), applier, JSON);
        String body = envelope("subscription.updated", baseSubscriptionData());
        stubValidRequest(body, "subscription.updated", 1);
        when(applier.apply(any(PaddleSubscriptionState.class), any(Instant.class), any(ApplySource.class)))
                .thenReturn(new ApplyResult(applied, SUBSCRIPTION_ID, USER_ID));

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository).updateOutcome(EVENT_ID, recorded.name(), SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    @DisplayName("data 안의 시각 형식이 틀리면 BadRequestException으로 바뀌어 나오고, 플랜·구독 캐시는 건드리지 않는다")
    void handleThrowsBadRequestWhenSubscriptionDataTimestampIsInvalid() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("updated_at", "not-a-timestamp");
        String body = envelope("subscription.updated", data);
        when(verifier.verify(SIGNATURE_HEADER, body)).thenReturn(true);
        // 파싱이 claim 앞에 오든 뒤에 오든 같은 계약이라 claim 스텁은 lenient
        lenient().when(billingEventRepository.tryClaim(
                eq(EVENT_ID), eq("subscription.updated"), eq(OCCURRED_AT), anyString(), any(Instant.class)))
                .thenReturn(1);

        assertThatThrownBy(() -> service.handle(SIGNATURE_HEADER, body))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(planService);
        verify(billingSubscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("웹훅 출처는 저장된 시각과 기준 시각(봉투 occurred_at)이 같은 알림도 반영한다 (API 출처처럼 UNCHANGED로 버리지 않는다)")
    void handleAppliesEventWhoseOrderingTimeEqualsStoredTime() {
        PaddleWebhookService service = service();
        String body = envelope("subscription.updated", baseSubscriptionData());
        stubValidRequest(body, "subscription.updated", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(existingSubscription(OCCURRED_AT)));

        service.handle(SIGNATURE_HEADER, body);

        verify(planService).activatePaid(USER_ID, PERIOD_ENDS_AT.plus(GRACE));
        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    @DisplayName("data.updated_at이 있으면 봉투 occurred_at보다 우선해 순서를 판정하고, 그 값을 last_event_occurred_at에 저장한다")
    void handleOrdersByDataUpdatedAtInsteadOfEnvelopeOccurredAt() {
        PaddleWebhookService service = service();
        Instant updatedAt = OCCURRED_AT.plusSeconds(120);
        Map<String, Object> data = baseSubscriptionData();
        data.put("updated_at", updatedAt.toString());
        String body = envelope("subscription.updated", data);
        stubValidRequest(body, "subscription.updated", 1);
        // 저장된 시각은 봉투 occurred_at보다 나중이지만 data.updated_at보다는 이전 → 봉투만 보면 STALE
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(existingSubscription(OCCURRED_AT.plusSeconds(60))));

        service.handle(SIGNATURE_HEADER, body);

        verify(billingEventRepository)
                .updateOutcome(EVENT_ID, BillingEventOutcome.APPLIED.name(), SUBSCRIPTION_ID, USER_ID);
        ArgumentCaptor<BillingSubscription> saved = ArgumentCaptor.forClass(BillingSubscription.class);
        verify(billingSubscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getLastEventOccurredAt()).isEqualTo(updatedAt);
    }

    // 실제 parser + 실제 applier를 조립한다 — 리포지토리·PlanService만 mock이라 반영 동작 단언이 그대로 의미를 갖는다
    private PaddleWebhookService service(String proPriceId) {
        PaddleSubscriptionApplier applier = new PaddleSubscriptionApplier(
                billingSubscriptionRepository,
                planService,
                new PaddleProperties("test-secret", Duration.ofSeconds(5), proPriceId, null, "sandbox", null),
                new BillingProperties(GRACE));
        return new PaddleWebhookService(
                verifier,
                billingEventRepository,
                new PaddleSubscriptionParser(),
                applier,
                JSON);
    }

    // 캐시 소유자·가격만 담은 스칼라 projection (items가 없거나 custom_data가 없는 알림에서 쓰인다)
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

    private BillingSubscription existingSubscription(Instant lastEventOccurredAt) {
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

    private String envelope(String eventType, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", EVENT_ID);
        envelope.put("event_type", eventType);
        envelope.put("occurred_at", OCCURRED_AT.toString());
        envelope.put("notification_id", "ntf_01");
        envelope.put("data", data);
        return writeJson(envelope);
    }

    private Map<String, Object> baseSubscriptionData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", SUBSCRIPTION_ID);
        data.put("status", "active");
        data.put("customer_id", CUSTOMER_ID);
        data.put("custom_data", Map.of("user_id", USER_ID.toString()));
        data.put("current_billing_period", Map.of(
                "starts_at", "2026-09-24T00:00:00Z",
                "ends_at", PERIOD_ENDS_AT.toString()));
        data.put("next_billed_at", PERIOD_ENDS_AT.toString());
        data.put("scheduled_change", null);
        data.put("canceled_at", null);
        data.put("items", List.of(Map.of("price", Map.of("id", PRICE_ID, "product_id", "pro_01m38fshezrep26abtbj1h1rde"))));
        return data;
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
