package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
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

// 같은 구독에 대한 두 반영이 겹칠 때, 먼저 끝난 쪽의 더 새로운 상태를 늦게 온 옛 알림이 덮어쓰지 않는지 확인한다.
// 잠그기 전에 구독 캐시를 엔티티로 읽으면 그 사본이 트랜잭션의 영속성 컨텍스트(1차 캐시)에 남아, 잠근 뒤에
// 다시 읽어도 DB가 아니라 옛 사본이 돌아온다 — Mockito 단위 테스트로는 재현할 수 없고 실제 커밋이 필요하다.
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@EnableConfigurationProperties({PaddleProperties.class, BillingProperties.class})
@Import({PaddleWebhookService.class, PaddleSubscriptionParser.class, PaddleSubscriptionApplier.class,
        PlanService.class, BetaPeriod.class})
// @DataJpaTest의 테스트 트랜잭션 감싸기를 꺼(NOT_SUPPORTED) handle()이 자기 트랜잭션을 직접 커밋하게 한다 —
// 감싸기에 합류하면 다른 스레드(JdbcTemplate)가 커밋한 행을 같은 트랜잭션이 보는 것부터 달라진다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(60)
@DisplayName("PaddleWebhookService: 사용자 잠금 뒤에 구독 캐시를 읽어야 동시에 커밋된 더 새로운 상태를 덮어쓰지 않는다")
class PaddleWebhookServiceLockOrderIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaddleWebhookServiceLockOrderIntegrationTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    private static final String EVENT_ID = "evt_lock_order_1";
    private static final String SUBSCRIPTION_ID = "sub_lock_order_1";
    private static final String CUSTOMER_ID = "ctm_lock_order_1";
    private static final String PRICE_ID = "pri_lock_order_1";

    // 기존 행(T1) < 이번 알림의 updated_at(T2) < 알림이 멈춘 사이 다른 쪽이 커밋한 행(T3)
    private static final Instant T1 = Instant.parse("2026-09-24T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-24T02:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-24T03:00:00Z");
    private static final Instant PERIOD_1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant PERIOD_2 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant PERIOD_3 = Instant.parse("2026-10-15T00:00:00Z");

    @Autowired
    private PaddleWebhookService paddleWebhookService;

    @MockitoBean
    private PaddleSignatureVerifier verifier;

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
                        VALUES ('github', ?, 'lock-order@example.com')
                        RETURNING id
                        """,
                UUID.class,
                UUID.randomUUID().toString());
        jdbcTemplate.update(
                """
                        INSERT INTO billing_subscriptions (
                            subscription_id, user_id, customer_id, status, price_id,
                            current_period_ends_at, last_event_occurred_at)
                        VALUES (?, ?, ?, 'active', ?, ?, ?)
                        """,
                SUBSCRIPTION_ID, userId, CUSTOMER_ID, PRICE_ID, Timestamp.from(PERIOD_1), Timestamp.from(T1));
    }

    @AfterEach
    void cleanUp() {
        // NOT_SUPPORTED라 실제로 커밋된다 — 다음 실행을 위해 직접 지운다 (users 삭제가 구독 캐시도 CASCADE로 지운다)
        jdbcTemplate.update("DELETE FROM billing_events WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM billing_subscriptions WHERE subscription_id = ?", SUBSCRIPTION_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("알림이 사용자 잠금 앞에서 멈춘 사이 더 새로운 상태가 커밋되면 → STALE, 그 행과 플랜이 그대로 남는다"
            + " (잠그기 전에 구독 캐시를 엔티티로 읽는 구현이면 옛 사본으로 덮어써 실패해야 한다)")
    void handleReturnsStaleAndKeepsNewerRowCommittedWhileWaitingForUserLock() throws Exception {
        String body = notificationBody();

        handleWhilePausedBeforeUserLock(body, () -> jdbcTemplate.update(
                """
                        UPDATE billing_subscriptions
                        SET status = 'canceled', canceled_at = ?, current_period_ends_at = ?,
                            last_event_occurred_at = ?
                        WHERE subscription_id = ?
                        """,
                Timestamp.from(T3), Timestamp.from(PERIOD_3), Timestamp.from(T3), SUBSCRIPTION_ID));

        assertThat(outcome()).isEqualTo("STALE");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, current_period_ends_at, last_event_occurred_at FROM billing_subscriptions"
                        + " WHERE subscription_id = ?", SUBSCRIPTION_ID);
        assertThat(row.get("status")).isEqualTo("canceled");
        assertThat(((Timestamp) row.get("last_event_occurred_at")).toInstant()).isEqualTo(T3);
        assertThat(((Timestamp) row.get("current_period_ends_at")).toInstant()).isEqualTo(PERIOD_3);
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT plan, plan_expires_at FROM users WHERE id = ?", userId);
        assertThat(user.get("plan")).isEqualTo("FREE");
        assertThat(user.get("plan_expires_at")).isNull();
    }

    @Test
    @DisplayName("custom_data·items가 없는 알림은 사용자와 가격을 스칼라 projection으로 찾는다 — 그 조회가 구독 엔티티를"
            + " 1차 캐시에 남기면 마찬가지로 옛 사본으로 덮어써 실패해야 한다")
    void handleReturnsStaleWhenUserAndPriceAreResolvedFromScalarProjection() throws Exception {
        String body = notificationBodyWithoutCustomDataAndItems();

        handleWhilePausedBeforeUserLock(body, () -> jdbcTemplate.update(
                """
                        UPDATE billing_subscriptions
                        SET status = 'canceled', canceled_at = ?, current_period_ends_at = ?,
                            last_event_occurred_at = ?
                        WHERE subscription_id = ?
                        """,
                Timestamp.from(T3), Timestamp.from(PERIOD_3), Timestamp.from(T3), SUBSCRIPTION_ID));

        assertThat(outcome()).isEqualTo("STALE");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, current_period_ends_at, last_event_occurred_at FROM billing_subscriptions"
                        + " WHERE subscription_id = ?", SUBSCRIPTION_ID);
        assertThat(row.get("status")).isEqualTo("canceled");
        assertThat(((Timestamp) row.get("last_event_occurred_at")).toInstant()).isEqualTo(T3);
        assertThat(((Timestamp) row.get("current_period_ends_at")).toInstant()).isEqualTo(PERIOD_3);
        assertThat(jdbcTemplate.queryForObject("SELECT plan FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("FREE");
    }

    @Test
    @DisplayName("대조군: 같은 방식으로 멈췄다 풀어도 그 사이 끼어든 커밋이 없으면 → APPLIED, 알림 상태가 반영된다")
    void handleAppliesWhenNothingNewerIsCommittedWhileWaitingForUserLock() throws Exception {
        String body = notificationBody();

        handleWhilePausedBeforeUserLock(body, () -> { });

        assertThat(outcome()).isEqualTo("APPLIED");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, current_period_ends_at, last_event_occurred_at FROM billing_subscriptions"
                        + " WHERE subscription_id = ?", SUBSCRIPTION_ID);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(((Timestamp) row.get("last_event_occurred_at")).toInstant()).isEqualTo(T2);
        assertThat(((Timestamp) row.get("current_period_ends_at")).toInstant()).isEqualTo(PERIOD_2);
        assertThat(jdbcTemplate.queryForObject("SELECT plan FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("PAID");
    }

    // handle()을 다른 스레드에서 돌려 lockForBillingUpdate에 들어서는 순간 멈춰 세우고, 그 사이 concurrentCommit을
    // 실행(= 다른 트랜잭션이 이미 커밋한 상황)한 뒤 풀어 준다. 멈춘 시점의 handle() 트랜잭션은 이미 열려 있고
    // 원장 선점(insert)까지 마쳤으므로, 구현이 잠금 전에 구독 캐시를 읽었다면 그 사본이 이미 1차 캐시에 올라 있다.
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

    private String outcome() {
        return jdbcTemplate.queryForObject(
                "SELECT outcome FROM billing_events WHERE event_id = ?", String.class, EVENT_ID);
    }

    // custom_data도 items도 없는 알림 — 사용자·가격을 캐시(스칼라 projection)에서만 알 수 있다
    private String notificationBodyWithoutCustomDataAndItems() {
        return """
                {
                  "event_id": "%s",
                  "event_type": "subscription.updated",
                  "occurred_at": "%s",
                  "notification_id": "ntf_lock_order_1",
                  "data": {
                    "id": "%s",
                    "status": "active",
                    "customer_id": "%s",
                    "updated_at": "%s",
                    "current_billing_period": {
                      "starts_at": "2026-10-01T00:00:00Z",
                      "ends_at": "%s"
                    },
                    "next_billed_at": "%s"
                  }
                }
                """.formatted(EVENT_ID, T2, SUBSCRIPTION_ID, CUSTOMER_ID, T2, PERIOD_2, PERIOD_2);
    }

    // 알림: 구독이 T2에 갱신됐고 기간이 PERIOD_2까지다 — 봉투 occurred_at도 같은 T2
    private String notificationBody() {
        return """
                {
                  "event_id": "%s",
                  "event_type": "subscription.updated",
                  "occurred_at": "%s",
                  "notification_id": "ntf_lock_order_1",
                  "data": {
                    "id": "%s",
                    "status": "active",
                    "customer_id": "%s",
                    "custom_data": {"user_id": "%s"},
                    "updated_at": "%s",
                    "current_billing_period": {
                      "starts_at": "2026-10-01T00:00:00Z",
                      "ends_at": "%s"
                    },
                    "next_billed_at": "%s",
                    "items": [{"price": {"id": "%s", "product_id": "pro_x"}}]
                  }
                }
                """.formatted(EVENT_ID, T2, SUBSCRIPTION_ID, CUSTOMER_ID, userId, T2, PERIOD_2, PERIOD_2, PRICE_ID);
    }
}
