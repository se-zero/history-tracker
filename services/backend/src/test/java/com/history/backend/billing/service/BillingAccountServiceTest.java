package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
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
import com.history.backend.billing.dto.CheckoutSessionResponse;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("BillingAccountService: 결제 상태·체크아웃·포털·코드 강등")
class BillingAccountServiceTest {

    private static final UUID USER_ID = UUID.fromString("d6e4a624-daf3-4561-ac6b-1ca067dd689b");
    private static final Instant EARLY = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant CANCELED_AT = Instant.parse("2026-08-15T00:00:00Z");

    @Mock
    private UserService userService;

    @Mock
    private PlanService planService;

    @Mock
    private BillingSubscriptionRepository subscriptionRepository;

    @Mock
    private PaddleApiClient paddleApiClient;

    private BillingAccountService service;

    @BeforeEach
    void setUp() {
        service = service(configuredProperties());
    }

    @Test
    @DisplayName("FREE는 NONE이고, 가장 최근 해지 시각과 결제 가능 여부를 돌려준다")
    void summaryOfFreeUserIncludesLatestCanceledAt() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCanceledAtDesc(USER_ID, "canceled"))
                .thenReturn(Optional.of(subscription("sub_old", "ctm_old", "canceled", EARLY, null, null, CANCELED_AT)));

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.planSource()).isEqualTo(PlanSource.NONE);
        assertThat(summary.subscription()).isNull();
        assertThat(summary.lastSubscriptionEndedAt()).isEqualTo(CANCELED_AT);
        assertThat(summary.checkoutAvailable()).isTrue();
    }

    @Test
    @DisplayName("만료 시각이 없고 살아 있는 구독도 없는 PAID는 전환 코드 사용자다")
    void summaryOfCodeUser() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of());

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.planSource()).isEqualTo(PlanSource.CODE);
        assertThat(summary.subscription()).isNull();
        assertThat(summary.lastSubscriptionEndedAt()).isNull();
        verify(subscriptionRepository, never())
                .findFirstByUserIdAndStatusOrderByCanceledAtDesc(any(), any());
    }

    @Test
    @DisplayName("살아 있는 구독이 있으면 만료 시각이 비어 있어도 구독자고, 기간 끝이 늦은 구독을 고른다")
    void summaryPrefersLatestLiveSubscriptionAndReportsScheduledCancel() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of(
                        subscription("sub_old", "ctm_old", "active", EARLY, null, null, null),
                        subscription("sub_new", "ctm_new", "past_due", LATE, "cancel", LATE, null)
                ));

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.planSource()).isEqualTo(PlanSource.SUBSCRIPTION);
        assertThat(summary.subscription().status()).isEqualTo("past_due");
        assertThat(summary.subscription().currentPeriodEndsAt()).isEqualTo(LATE);
        assertThat(summary.subscription().cancelScheduledAt()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("해지 예약이 아닌 scheduled change는 해지 시각으로 보여 주지 않는다")
    void summaryOmitsCancelAtWhenScheduledChangeIsNotCancel() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, LATE));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of(subscription("sub_1", "ctm_1", "active", LATE, "pause", LATE, null)));

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.planSource()).isEqualTo(PlanSource.SUBSCRIPTION);
        assertThat(summary.subscription().cancelScheduledAt()).isNull();
    }

    @Test
    @DisplayName("만료 시각이 있는 PAID인데 캐시에 살아 있는 구독이 없어도 SUBSCRIPTION이다")
    void summaryOfPaidUserWithoutLiveSubscriptionStaysSubscription() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, LATE));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of());

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.planSource()).isEqualTo(PlanSource.SUBSCRIPTION);
        assertThat(summary.subscription()).isNull();
    }

    @Test
    @DisplayName("설정이 비어 있으면 checkoutAvailable이 false다")
    void summaryReportsCheckoutUnavailableWhenUnconfigured() {
        BillingAccountService unconfigured = service(unconfiguredProperties());
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCanceledAtDesc(USER_ID, "canceled"))
                .thenReturn(Optional.empty());

        assertThat(unconfigured.summary(USER_ID).checkoutAvailable()).isFalse();
        assertThat(unconfigured.checkoutAvailable()).isFalse();
    }

    @Test
    @DisplayName("탈퇴한 사용자 요약은 404이고 Paddle을 부르지 않는다")
    void summaryRejectsInactiveUser() {
        when(userService.getActiveUser(USER_ID)).thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.summary(USER_ID))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(paddleApiClient, subscriptionRepository);
    }

    @Test
    @DisplayName("FREE이고 살아 있는 구독이 없고 설정이 있을 때만 거래를 만든다")
    void startCheckoutCreatesTransactionForFreeUser() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of());
        when(paddleApiClient.createCheckoutTransaction("pri_pro", USER_ID)).thenReturn("txn_1");

        CheckoutSessionResponse response = service.startCheckout(USER_ID);

        assertThat(response.transactionId()).isEqualTo("txn_1");
        assertThat(response.clientToken()).isEqualTo("test_token");
        assertThat(response.environment()).isEqualTo("sandbox");
    }

    @Test
    @DisplayName("environment가 비어 있으면 체크아웃 응답의 환경은 sandbox다")
    void startCheckoutDefaultsBlankEnvironmentToSandbox() {
        service = service(new PaddleProperties(
                "whsec", Duration.ofSeconds(5), "pri_pro", "key", "  ", "test_token"));
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of());
        when(paddleApiClient.createCheckoutTransaction("pri_pro", USER_ID)).thenReturn("txn_1");

        assertThat(service.startCheckout(USER_ID).environment()).isEqualTo("sandbox");
    }

    @Test
    @DisplayName("코드 사용자·구독자·미설정·이미 구독 중이면 체크아웃은 409이고 거래를 만들지 않는다")
    void startCheckoutRejectsPaidConfiguredAndExistingSubscription() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, null));
        assertThatThrownBy(() -> service.startCheckout(USER_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Checkout is only available on the free plan.");

        service = service(unconfiguredProperties());
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        assertThatThrownBy(() -> service.startCheckout(USER_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Checkout is not configured.");

        service = service(configuredProperties());
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of(subscription("sub_1", "ctm_1", "active", LATE, null, null, null)));
        assertThatThrownBy(() -> service.startCheckout(USER_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An active subscription already exists.");

        verify(paddleApiClient, never()).createCheckoutTransaction(any(), any());
    }

    @Test
    @DisplayName("포털은 요청값이 아니라 캐시의 customer id로 열고, target에 맞는 링크를 고른다")
    void openPortalUsesCachedCustomerAndSelectedTarget() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, LATE));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of(
                        subscription("sub_old", "ctm_old", "active", EARLY, null, null, null),
                        subscription("sub_new", "ctm_new", "active", LATE, null, null, null)
                ));
        when(paddleApiClient.createPortalSession("ctm_new", "sub_new"))
                .thenReturn(new PaddleApiClient.PortalUrls("https://portal.test/overview", "https://portal.test/cancel"));

        BillingPortalResponse overview = service.openPortal(USER_ID, "overview");
        BillingPortalResponse cancel = service.openPortal(USER_ID, "cancel");

        assertThat(overview.url()).isEqualTo("https://portal.test/overview");
        assertThat(cancel.url()).isEqualTo("https://portal.test/cancel");
    }

    @Test
    @DisplayName("살아 있는 구독이 없으면 포털은 409이고 Paddle을 부르지 않는다")
    void openPortalRejectsUserWithoutLiveSubscription() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.FREE, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.openPortal(USER_ID, "overview"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("No active subscription.");
        verifyNoInteractions(paddleApiClient);
    }

    @Test
    @DisplayName("알 수 없는 포털 target은 400이고 Paddle을 부르지 않는다")
    void openPortalRejectsUnknownTarget() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, LATE));

        assertThatThrownBy(() -> service.openPortal(USER_ID, "refund"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown portal target.");
        verifyNoInteractions(paddleApiClient);
    }

    @Test
    @DisplayName("살아 있는 구독이 없으면 코드 강등을 호출하고, 있으면 409로 막는다")
    void downgradeCodePlanBlocksLiveSubscription() {
        when(userService.getActiveUser(USER_ID)).thenReturn(user(Plan.PAID, null));
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of());

        service.downgradeCodePlan(USER_ID);
        verify(planService).downgradeCodeUserToFree(USER_ID);

        when(subscriptionRepository.findByUserIdAndStatusIn(eq(USER_ID), argThat(this::isLiveStatuses)))
                .thenReturn(List.of(subscription("sub_1", "ctm_1", "trialing", LATE, null, null, null)));
        assertThatThrownBy(() -> service.downgradeCodePlan(USER_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An active subscription cannot be downgraded here.");
        verify(planService).downgradeCodeUserToFree(USER_ID);
    }

    @Test
    @DisplayName("탈퇴한 사용자의 체크아웃·강등은 404이고 그 뒤 일은 하지 않는다")
    void mutatingEndpointsRejectInactiveUser() {
        when(userService.getActiveUser(USER_ID)).thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.startCheckout(USER_ID)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.downgradeCodePlan(USER_ID)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(paddleApiClient, planService, subscriptionRepository);
    }

    private BillingAccountService service(PaddleProperties properties) {
        return new BillingAccountService(
                userService, planService, subscriptionRepository, paddleApiClient, properties);
    }

    private boolean isLiveStatuses(Collection<String> statuses) {
        return statuses != null
                && statuses.size() == 3
                && statuses.containsAll(List.of("active", "trialing", "past_due"));
    }

    private PaddleProperties configuredProperties() {
        return new PaddleProperties("whsec", Duration.ofSeconds(5), "pri_pro", "key", "sandbox", "test_token");
    }

    private PaddleProperties unconfiguredProperties() {
        return new PaddleProperties("whsec", Duration.ofSeconds(5), "", null, "sandbox", null);
    }

    private User user(Plan plan, Instant planExpiresAt) {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "plan", plan);
        ReflectionTestUtils.setField(user, "planExpiresAt", planExpiresAt);
        return user;
    }

    private BillingSubscription subscription(
            String subscriptionId,
            String customerId,
            String status,
            Instant periodEnd,
            String scheduledAction,
            Instant scheduledAt,
            Instant canceledAt
    ) {
        return new BillingSubscription(
                subscriptionId,
                USER_ID,
                customerId,
                status,
                "pri_pro",
                periodEnd,
                scheduledAction,
                scheduledAt,
                canceledAt,
                EARLY
        );
    }
}
