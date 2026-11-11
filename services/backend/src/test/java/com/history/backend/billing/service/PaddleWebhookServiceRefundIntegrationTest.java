package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.history.backend.auth.service.BetaPeriod;
import com.history.backend.auth.service.PlanService;
import com.history.backend.billing.BillingProperties;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 승인된 환불 알림이 실제 트랜잭션 경계(원장 선점 → Paddle 해지 → 반영 → outcome 기록) 안에서 한 번에 커밋되거나,
// 해지가 실패하면 원장까지 통째로 롤백돼 Paddle이 재시도할 수 있는지 확인한다.
// Mockito 단위 테스트로는 재현할 수 없다 — 실제 @Transactional 프록시·롤백·행 잠금·1차 캐시가 핵심이다.
// PaddleApiClient만 mock이다(Paddle 호출은 실제로 나갈 수 없다).
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "paddle.pro-price-id=" + PaddleWebhookServiceRefundIntegrationTest.PRO_PRICE_ID
})
@EnableConfigurationProperties({PaddleProperties.class, BillingProperties.class})
@Import({PaddleWebhookService.class, PaddleSubscriptionParser.class, PaddleSubscriptionApplier.class,
        SubscriptionRefundService.class, PlanService.class, BetaPeriod.class})
// @DataJpaTest의 테스트 트랜잭션 감싸기를 꺼(NOT_SUPPORTED) handle()이 자기 트랜잭션을 직접 커밋·롤백하게 한다 —
// 감싸기에 합류하면 커밋도 롤백도 일어나지 않아 결함이 있어도 조용히 통과해버린다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(60)
@DisplayName("PaddleWebhookService: 승인된 환불 알림의 해지·반영이 한 트랜잭션으로 커밋되고, 실패하면 원장까지 롤백된다")
class PaddleWebhookServiceRefundIntegrationTest {

    static final String PRO_PRICE_ID = "pri_refund_it_pro";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaddleWebhookServiceRefundIntegrationTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    private static final String EVENT_ID = "evt_refund_it_1";
    private static final String SUBSCRIPTION_ID = "sub_refund_it_1";
    private static final String CUSTOMER_ID = "ctm_refund_it_1";

    // 기존 캐시 행(T1) < 해지 응답의 updated_at(T2) < 해지 반영이 멈춘 사이 다른 쪽이 커밋한 행(T3)
    private static final Instant T1 = Instant.parse("2026-11-10T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T02:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant PERIOD_ENDS_AT = Instant.parse("2026-12-10T00:00:00Z");

    @Autowired
    private PaddleWebhookService paddleWebhookService;

    @MockitoBean
    private PaddleSignatureVerifier verifier;

    @MockitoBean
    private PaddleApiClient paddleApiClient;

    // 실제 PlanService를 그대로 쓰되 lockForBillingUpdate 직전에 멈출 수 있게 spy로 감싼다
    @MockitoSpyBean
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void setUp() {
        when(verifier.verify(any(), any())).thenReturn(true);
        userId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO users (provider, provider_user_id, email)
                        VALUES ('github', ?, 'refund-it@example.com')
                        RETURNING id
                        """,
                UUID.class,
                UUID.randomUUID().toString());
        // 결제 중인 Pro 사용자: 플랜은 PAID, 구독 캐시는 active
        jdbcTemplate.update("UPDATE users SET plan = 'PAID', plan_expires_at = ? WHERE id = ?",
                Timestamp.from(PERIOD_ENDS_AT), userId);
        jdbcTemplate.update(
                """
                        INSERT INTO billing_subscriptions (
                            subscription_id, user_id, customer_id, status, price_id,
                            current_period_ends_at, last_event_occurred_at)
                        VALUES (?, ?, ?, 'active', ?, ?, ?)
                        """,
                SUBSCRIPTION_ID, userId, CUSTOMER_ID, PRO_PRICE_ID,
                Timestamp.from(PERIOD_ENDS_AT), Timestamp.from(T1));
    }

    @AfterEach
    void cleanUp() {
        // NOT_SUPPORTED라 실제로 커밋된다 — 다음 실행을 위해 직접 지운다 (users 삭제가 구독 캐시도 CASCADE로 지운다)
        jdbcTemplate.update("DELETE FROM billing_events WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM billing_subscriptions WHERE subscription_id = ?", SUBSCRIPTION_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("승인된 환불 알림 → 즉시 해지 응답이 반영돼 users.plan=FREE, 캐시 status=canceled, billing_events.outcome=APPLIED가 함께 커밋된다")
    void handleCommitsFreePlanCanceledCacheAndAppliedOutcome() {
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(activeState());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY))
                .thenReturn(canceledState());

        paddleWebhookService.handle("ts=1758682800;h1=deadbeef", approvedRefundBody());

        verify(paddleApiClient).cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY);
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT plan, plan_expires_at FROM users WHERE id = ?", userId);
        assertThat(user.get("plan")).isEqualTo("FREE");
        assertThat(user.get("plan_expires_at")).isNull();
        Map<String, Object> subscription = jdbcTemplate.queryForMap(
                "SELECT status, canceled_at, last_event_occurred_at FROM billing_subscriptions"
                        + " WHERE subscription_id = ?", SUBSCRIPTION_ID);
        assertThat(subscription.get("status")).isEqualTo("canceled");
        assertThat(((Timestamp) subscription.get("canceled_at")).toInstant()).isEqualTo(T2);
        assertThat(((Timestamp) subscription.get("last_event_occurred_at")).toInstant()).isEqualTo(T2);
        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT outcome, subscription_id, user_id FROM billing_events WHERE event_id = ?", EVENT_ID);
        assertThat(event.get("outcome")).isEqualTo("APPLIED");
        assertThat(event.get("subscription_id")).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.get("user_id")).isEqualTo(userId);
    }

    @Test
    @DisplayName("즉시 해지가 BadGatewayException을 던지면 예외가 전파되고 billing_events에 그 event_id 행이 없다(원장 롤백),"
            + " 플랜과 캐시도 그대로다 — Paddle이 같은 알림을 다시 보내 재시도된다")
    void handleRollsBackClaimAndKeepsPlanWhenCancelFails() {
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(activeState());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY))
                .thenThrow(new BadGatewayException("Paddle subscription cancel request failed."));

        assertThatThrownBy(() -> paddleWebhookService.handle("ts=1758682800;h1=deadbeef", approvedRefundBody()))
                .isInstanceOf(BadGatewayException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_events WHERE event_id = ?", Integer.class, EVENT_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT plan FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM billing_subscriptions WHERE subscription_id = ?", String.class, SUBSCRIPTION_ID))
                .isEqualTo("active");
    }

    @Test
    @DisplayName("해지 반영이 사용자 잠금 앞에서 멈춘 사이 더 새로운 상태가 커밋되면 → STALE, 그 행과 플랜이 그대로 남는다"
            + " (환불 서비스가 잠그기 전에 구독 캐시를 엔티티로 읽으면 옛 사본으로 덮어써 실패해야 한다)")
    void handleReturnsStaleAndKeepsNewerRowCommittedWhileWaitingForUserLock() throws Exception {
        when(paddleApiClient.fetchSubscription(SUBSCRIPTION_ID)).thenReturn(activeState());
        when(paddleApiClient.cancelSubscription(SUBSCRIPTION_ID, EffectiveFrom.IMMEDIATELY))
                .thenReturn(canceledState());

        handleWhilePausedBeforeUserLock(approvedRefundBody(), () -> jdbcTemplate.update(
                """
                        UPDATE billing_subscriptions
                        SET status = 'active', current_period_ends_at = ?, last_event_occurred_at = ?
                        WHERE subscription_id = ?
                        """,
                Timestamp.from(PERIOD_ENDS_AT.plusSeconds(86_400)), Timestamp.from(T3), SUBSCRIPTION_ID));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT outcome FROM billing_events WHERE event_id = ?", String.class, EVENT_ID)).isEqualTo("STALE");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, last_event_occurred_at FROM billing_subscriptions WHERE subscription_id = ?",
                SUBSCRIPTION_ID);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(((Timestamp) row.get("last_event_occurred_at")).toInstant()).isEqualTo(T3);
        assertThat(jdbcTemplate.queryForObject("SELECT plan FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("PAID");
    }

    // handle()을 다른 스레드에서 돌려 lockForBillingUpdate에 들어서는 순간 멈춰 세우고, 그 사이 concurrentCommit을
    // 실행(= 다른 트랜잭션이 이미 커밋한 상황)한 뒤 풀어 준다. 멈춘 시점의 handle() 트랜잭션은 이미 열려 있고
    // Paddle 호출까지 마쳤으므로, 그 전에 구독 캐시를 엔티티로 읽은 구현이면 사본이 이미 1차 캐시에 올라 있다.
    private void handleWhilePausedBeforeUserLock(String body, Runnable concurrentCommit) throws Exception {
        CountDownLatch reachedLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            reachedLock.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 잠금 대기를 풀어 주지 않았다");
            }
            return invocation.callRealMethod();
        }).when(planService).lockForBillingUpdate(userId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = executor.submit(
                    () -> paddleWebhookService.handle("ts=1758682800;h1=deadbeef", body));
            assertThat(reachedLock.await(15, TimeUnit.SECONDS))
                    .as("handle()이 PlanService.lockForBillingUpdate에 도달해야 한다 (사용자 잠금을 거치지 않았다)")
                    .isTrue();
            concurrentCommit.run();
            release.countDown();
            handling.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // 결제 중인 Pro 구독 — 예약 변경 없음 (시계에 의존하는 보호 장치를 타지 않는다)
    private PaddleSubscriptionState activeState() {
        return new PaddleSubscriptionState(
                SUBSCRIPTION_ID, "active", CUSTOMER_ID, List.of(PRO_PRICE_ID), userId.toString(),
                null, null, null, PERIOD_ENDS_AT, PERIOD_ENDS_AT, T1);
    }

    // 즉시 해지의 응답 — canceled, 기간·결제 예정일 없음
    private PaddleSubscriptionState canceledState() {
        return new PaddleSubscriptionState(
                SUBSCRIPTION_ID, "canceled", CUSTOMER_ID, List.of(PRO_PRICE_ID), userId.toString(),
                null, null, T2, null, null, T2);
    }

    private String approvedRefundBody() {
        return """
                {
                  "event_id": "%s",
                  "event_type": "adjustment.created",
                  "occurred_at": "2026-11-10T03:00:00Z",
                  "notification_id": "ntf_refund_it_1",
                  "data": {
                    "id": "adj_refund_it_1",
                    "action": "refund",
                    "type": "full",
                    "status": "approved",
                    "transaction_id": "txn_refund_it_1",
                    "subscription_id": "%s",
                    "customer_id": "%s"
                  }
                }
                """.formatted(EVENT_ID, SUBSCRIPTION_ID, CUSTOMER_ID);
    }
}
