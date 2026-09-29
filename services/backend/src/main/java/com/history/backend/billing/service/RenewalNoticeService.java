package com.history.backend.billing.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.ResendProperties;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 갱신 결제 약 7일 전 안내. Paddle은 한국 월 구독에 이 메일을 보내지 않는다.
// 월 구독에 법 의무가 있는지는 미확인이라, 본문은 결제일·금액·해지 방법만 적고 법령은 인용하지 않는다.
@Slf4j
@Service
public class RenewalNoticeService {

    static final Duration WINDOW_START = Duration.ofDays(6);
    static final Duration WINDOW_END = Duration.ofDays(8);
    private static final Set<String> NOTICE_STATUSES = Set.of("active", "trialing");
    private static final DateTimeFormatter PAYMENT_DATE = DateTimeFormatter.ofPattern(
            "yyyy년 M월 d일", Locale.KOREAN).withZone(ZoneId.of("Asia/Seoul"));

    private final BillingSubscriptionRepository subscriptionRepository;
    private final PaddleApiClient paddleApiClient;
    private final ResendClient resendClient;
    private final PaddleProperties paddleProperties;
    private final ResendProperties resendProperties;

    public RenewalNoticeService(
            BillingSubscriptionRepository subscriptionRepository,
            PaddleApiClient paddleApiClient,
            ResendClient resendClient,
            PaddleProperties paddleProperties,
            ResendProperties resendProperties
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.paddleApiClient = paddleApiClient;
        this.resendClient = resendClient;
        this.paddleProperties = paddleProperties;
        this.resendProperties = resendProperties;
    }

    public int sendDueNotices(Instant now) {
        if (!resendProperties.isConfigured()
                || paddleProperties.apiKey() == null
                || paddleProperties.apiKey().isBlank()) {
            log.info("Billing renewal notice skipped; mail or Paddle API key is not configured.");
            return 0;
        }
        Instant windowStart = now.plus(WINDOW_START);
        Instant windowEnd = now.plus(WINDOW_END);
        List<BillingSubscription> candidates = subscriptionRepository
                .findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(windowStart, windowEnd);
        int sent = 0;
        for (BillingSubscription subscription : candidates) {
            if (!shouldNotify(subscription)) {
                continue;
            }
            try {
                String email = paddleApiClient.getCustomerEmail(subscription.getCustomerId());
                if (email == null || email.isBlank()) {
                    log.warn("Billing renewal notice skipped; Paddle customer has no email. subscriptionId={}",
                            subscription.getSubscriptionId());
                    continue;
                }
                resendClient.send(new OutboundEmail(
                        email,
                        "whycode Pro 결제가 7일 뒤 있습니다",
                        body(subscription)
                ));
                subscription.recordRenewalNoticePeriodEnd(subscription.getCurrentPeriodEndsAt());
                subscriptionRepository.save(subscription);
                sent++;
            } catch (RuntimeException exception) {
                log.warn("Billing renewal notice failed. subscriptionId={} errorType={}",
                        subscription.getSubscriptionId(), exception.getClass().getSimpleName());
            }
        }
        return sent;
    }

    private boolean shouldNotify(BillingSubscription subscription) {
        if (!NOTICE_STATUSES.contains(subscription.getStatus())) {
            return false;
        }
        if ("cancel".equals(subscription.getScheduledChangeAction())) {
            return false;
        }
        Instant periodEnd = subscription.getCurrentPeriodEndsAt();
        return periodEnd != null && !periodEnd.equals(subscription.getRenewalNoticePeriodEnd());
    }

    private String body(BillingSubscription subscription) {
        String paymentDate = PAYMENT_DATE.format(subscription.getCurrentPeriodEndsAt());
        return """
                whycode Pro 구독의 다음 결제일은 %s입니다.
                금액은 월 14,900원(부가세 포함)입니다.
                해지하려면 계정 설정의 구독 해지를 누르세요. 해지하면 이미 결제한 기간이 끝날 때까지 Pro를 쓸 수 있고, 다음 결제는 없습니다.

                문의: contact@why-code.com
                """.formatted(paymentDate);
    }
}
