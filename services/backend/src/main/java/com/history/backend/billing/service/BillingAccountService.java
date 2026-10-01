package com.history.backend.billing.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.PlanService;
import com.history.backend.auth.service.UserService;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.dto.BillingPortalResponse;
import com.history.backend.billing.dto.BillingSummaryResponse;
import com.history.backend.billing.dto.BillingSummaryResponse.PlanSource;
import com.history.backend.billing.dto.BillingSummaryResponse.SubscriptionView;
import com.history.backend.billing.dto.CheckoutSessionResponse;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.ConflictException;
import org.springframework.stereotype.Service;

// 로그인한 사용자의 결제 상태와 체크아웃·포털 진입. Paddle 호출은 트랜잭션 밖에서 한다.
@Service
public class BillingAccountService {

    // PaddleWebhookService.ACTIVE_LIKE_STATUSES와 같은 집합. paused는 여기 넣지 않는다 —
    // 일시정지는 이미 강등 대상이고, 체크아웃을 막을 "살아 있는 구독"이 아니다.
    static final List<String> LIVE_STATUSES = List.of("active", "trialing", "past_due");

    private final UserService userService;
    private final PlanService planService;
    private final BillingSubscriptionRepository subscriptionRepository;
    private final PaddleApiClient paddleApiClient;
    private final PaddleProperties paddleProperties;

    public BillingAccountService(
            UserService userService,
            PlanService planService,
            BillingSubscriptionRepository subscriptionRepository,
            PaddleApiClient paddleApiClient,
            PaddleProperties paddleProperties
    ) {
        this.userService = userService;
        this.planService = planService;
        this.subscriptionRepository = subscriptionRepository;
        this.paddleApiClient = paddleApiClient;
        this.paddleProperties = paddleProperties;
    }

    public boolean checkoutAvailable() {
        return paddleProperties.isCheckoutConfigured();
    }

    public BillingSummaryResponse summary(UUID userId) {
        User user = userService.getActiveUser(userId);
        List<BillingSubscription> live = liveSubscriptions(userId);
        BillingSubscription primary = primary(live).orElse(null);
        PlanSource planSource = planSource(user, live);
        SubscriptionView subscription = null;
        if (planSource == PlanSource.SUBSCRIPTION && primary != null) {
            subscription = new SubscriptionView(
                    primary.getStatus(),
                    primary.getCurrentPeriodEndsAt(),
                    "cancel".equals(primary.getScheduledChangeAction())
                            ? primary.getScheduledChangeEffectiveAt()
                            : null
            );
        }
        return new BillingSummaryResponse(
                planSource,
                subscription,
                user.getPlan() == Plan.FREE ? latestCanceledAt(userId) : null,
                checkoutAvailable()
        );
    }

    public CheckoutSessionResponse startCheckout(UUID userId) {
        User user = userService.getActiveUser(userId);
        if (!checkoutAvailable()) {
            throw new ConflictException("Checkout is not configured.");
        }
        if (user.getPlan() != Plan.FREE) {
            throw new ConflictException("Checkout is only available on the free plan.");
        }
        if (!liveSubscriptions(userId).isEmpty()) {
            throw new ConflictException("An active subscription already exists.");
        }
        String transactionId = paddleApiClient.createCheckoutTransaction(paddleProperties.proPriceId(), userId);
        return new CheckoutSessionResponse(transactionId, paddleProperties.clientToken(), environment());
    }

    public BillingPortalResponse openPortal(UUID userId, String target) {
        userService.getActiveUser(userId);
        if (!"overview".equals(target) && !"cancel".equals(target)) {
            throw new BadRequestException("Unknown portal target.");
        }
        BillingSubscription subscription = primary(liveSubscriptions(userId))
                .orElseThrow(() -> new ConflictException("No active subscription."));
        // customer id는 요청으로 받지 않는다. 받으면 다른 고객의 포털을 열 수 있다.
        PaddleApiClient.PortalUrls urls = paddleApiClient.createPortalSession(
                subscription.getCustomerId(), subscription.getSubscriptionId());
        String url = "cancel".equals(target) ? urls.cancel() : urls.overview();
        return new BillingPortalResponse(url);
    }

    public void downgradeCodePlan(UUID userId) {
        userService.getActiveUser(userId);
        if (!liveSubscriptions(userId).isEmpty()) {
            throw new ConflictException("An active subscription cannot be downgraded here.");
        }
        planService.downgradeCodeUserToFree(userId);
    }

    private PlanSource planSource(User user, List<BillingSubscription> live) {
        if (user.getPlan() != Plan.PAID) {
            return PlanSource.NONE;
        }
        if (user.getPlanExpiresAt() == null && live.isEmpty()) {
            return PlanSource.CODE;
        }
        return PlanSource.SUBSCRIPTION;
    }

    private List<BillingSubscription> liveSubscriptions(UUID userId) {
        return subscriptionRepository.findByUserIdAndStatusIn(userId, LIVE_STATUSES);
    }

    private Optional<BillingSubscription> primary(List<BillingSubscription> subscriptions) {
        return subscriptions.stream().max(Comparator.comparing(
                BillingSubscription::getCurrentPeriodEndsAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Instant latestCanceledAt(UUID userId) {
        return subscriptionRepository.findFirstByUserIdAndStatusOrderByCanceledAtDesc(userId, "canceled")
                .map(BillingSubscription::getCanceledAt)
                .orElse(null);
    }

    private String environment() {
        String environment = paddleProperties.environment();
        return environment == null || environment.isBlank() ? "sandbox" : environment;
    }
}
