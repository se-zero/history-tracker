package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.ResendProperties;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalNoticeService: 결제일 7일 전 안내")
class RenewalNoticeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant IN_WINDOW = NOW.plus(Duration.ofDays(7));
    private static final UUID USER_ID = UUID.fromString("d6e4a624-daf3-4561-ac6b-1ca067dd689b");

    @Mock
    private BillingSubscriptionRepository subscriptionRepository;

    @Mock
    private PaddleApiClient paddleApiClient;

    @Mock
    private ResendClient resendClient;

    private RenewalNoticeService service;

    @BeforeEach
    void setUp() {
        service = new RenewalNoticeService(
                subscriptionRepository,
                paddleApiClient,
                resendClient,
                configuredPaddle(),
                configuredResend()
        );
    }

    @Test
    @DisplayName("6일 이상 8일 미만인 active 구독에만 메일을 보내고, 그 주기 끝을 기록한다")
    void sendsOnceForSubscriptionInsideTheWindow() {
        BillingSubscription due = subscription("sub_due", "active", IN_WINDOW, null, null);
        when(subscriptionRepository.findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(
                NOW.plus(Duration.ofDays(6)), NOW.plus(Duration.ofDays(8))))
                .thenReturn(List.of(
                        due,
                        subscription("sub_early", "active", NOW.plus(Duration.ofDays(5)), null, null),
                        subscription("sub_late", "active", NOW.plus(Duration.ofDays(8)), null, null),
                        subscription("sub_due_status", "past_due", IN_WINDOW, null, null)
                ));
        when(paddleApiClient.getCustomerEmail("ctm_sub_due")).thenReturn("payer@example.com");

        int sent = service.sendDueNotices(NOW);

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<OutboundEmail> mail = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(resendClient).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("payer@example.com");
        assertThat(mail.getValue().subject()).contains("7일");
        assertThat(mail.getValue().text()).contains("14,900");
        assertThat(mail.getValue().text()).doesNotContain("법령");
        assertThat(due.getRenewalNoticePeriodEnd()).isEqualTo(IN_WINDOW);
        verify(subscriptionRepository).save(due);
    }

    @Test
    @DisplayName("해지 예약이 있거나 이번 주기에 이미 보낸 구독은 건너뛴다")
    void skipsScheduledCancelAndAlreadyNotifiedPeriod() {
        when(subscriptionRepository.findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(
                any(), any()))
                .thenReturn(List.of(
                        subscription("sub_cancel", "active", IN_WINDOW, "cancel", null),
                        subscription("sub_sent", "trialing", IN_WINDOW, null, IN_WINDOW)
                ));

        int sent = service.sendDueNotices(NOW);

        assertThat(sent).isZero();
        verifyNoInteractions(paddleApiClient, resendClient);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("예전 주기에 보낸 기록은 새 주기 안내를 막지 않는다")
    void sendsAgainWhenThePeriodEndChanged() {
        BillingSubscription renewed = subscription("sub_renewed", "active", IN_WINDOW, null, IN_WINDOW.minus(Duration.ofDays(30)));
        when(subscriptionRepository.findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(
                any(), any())).thenReturn(List.of(renewed));
        when(paddleApiClient.getCustomerEmail("ctm_sub_renewed")).thenReturn("payer@example.com");

        assertThat(service.sendDueNotices(NOW)).isEqualTo(1);
        verify(subscriptionRepository).save(renewed);
    }

    @Test
    @DisplayName("고객 이메일이 없거나 한 건이 실패해도 나머지는 보내고, 실패 건은 보낸 것으로 표시하지 않는다")
    void continuesWhenOneSubscriptionFails() {
        BillingSubscription missingEmail = subscription("sub_missing", "active", IN_WINDOW, null, null);
        BillingSubscription failed = subscription("sub_fail", "active", IN_WINDOW, null, null);
        BillingSubscription ok = subscription("sub_ok", "active", IN_WINDOW, null, null);
        when(subscriptionRepository.findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(
                any(), any())).thenReturn(List.of(missingEmail, failed, ok));
        when(paddleApiClient.getCustomerEmail("ctm_sub_missing")).thenReturn("  ");
        when(paddleApiClient.getCustomerEmail("ctm_sub_fail"))
                .thenThrow(new BadGatewayException("Paddle customer request failed."));
        when(paddleApiClient.getCustomerEmail("ctm_sub_ok")).thenReturn("ok@example.com");

        int sent = service.sendDueNotices(NOW);

        assertThat(sent).isEqualTo(1);
        verify(subscriptionRepository).save(ok);
        verify(subscriptionRepository, never()).save(missingEmail);
        verify(subscriptionRepository, never()).save(failed);
        assertThat(failed.getRenewalNoticePeriodEnd()).isNull();
    }

    @Test
    @DisplayName("Resend 키나 Paddle API 키가 비면 조회도 발송도 하지 않는다")
    void skipsTheRunWhenMailOrPaddleIsNotConfigured() {
        RenewalNoticeService unconfiguredMail = new RenewalNoticeService(
                subscriptionRepository, paddleApiClient, resendClient, configuredPaddle(),
                new ResendProperties(" ", "from", "reply"));
        RenewalNoticeService unconfiguredPaddle = new RenewalNoticeService(
                subscriptionRepository, paddleApiClient, resendClient,
                new PaddleProperties("whsec", Duration.ofSeconds(5), "pri", null, "sandbox", "tok"),
                configuredResend());

        assertThat(unconfiguredMail.sendDueNotices(NOW)).isZero();
        assertThat(unconfiguredPaddle.sendDueNotices(NOW)).isZero();
        verifyNoInteractions(subscriptionRepository, paddleApiClient, resendClient);
    }

    private PaddleProperties configuredPaddle() {
        return new PaddleProperties("whsec", Duration.ofSeconds(5), "pri", "pdl_sdbx_apikey_test", "sandbox", "test_token");
    }

    private ResendProperties configuredResend() {
        return new ResendProperties("re_test", "whycode <billing@mail.why-code.com>", "contact@why-code.com");
    }

    private BillingSubscription subscription(
            String subscriptionId,
            String status,
            Instant periodEnd,
            String scheduledAction,
            Instant noticePeriodEnd
    ) {
        BillingSubscription subscription = new BillingSubscription(
                subscriptionId,
                USER_ID,
                "ctm_" + subscriptionId,
                status,
                "pri_pro",
                periodEnd,
                scheduledAction,
                scheduledAction == null ? null : periodEnd,
                null,
                NOW
        );
        subscription.recordRenewalNoticePeriodEnd(noticePeriodEnd);
        return subscription;
    }
}
