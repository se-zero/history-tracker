package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
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
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @DisplayName("custom_data.user_id도 없고 기존 구독도 없으면 → UNMATCHED, 예외 없이 종료")
    void handleRecordsUnmatchedWhenNoUserIdResolvable() {
        PaddleWebhookService service = service();
        Map<String, Object> data = baseSubscriptionData();
        data.put("custom_data", null);
        String body = envelope("subscription.created", data);
        stubValidRequest(body, "subscription.created", 1);
        when(billingSubscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

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
        verifyNoInteractions(planService);
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
        verifyNoInteractions(planService);
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

    private PaddleWebhookService service(String proPriceId) {
        return new PaddleWebhookService(
                verifier,
                billingEventRepository,
                billingSubscriptionRepository,
                planService,
                new PaddleProperties("test-secret", Duration.ofSeconds(5), proPriceId),
                new BillingProperties(GRACE),
                JSON);
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
