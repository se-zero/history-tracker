package com.history.backend.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.history.backend.billing.domain.BillingSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 결제일 안내 표시(renewal_notice_period_end)를 지키는 두 장치를 실제 커밋으로 확인한다.
//  (a) RenewalNoticeService는 트랜잭션 없이 호출하므로, 단독 UPDATE는 스스로 트랜잭션을 가져야 한다.
//  (b) 웹훅·동기화가 만드는 새 BillingSubscription 객체는 안내 표시를 모른다 — 통째 저장이 그 칸을 지우면 같은 주기에 메일이 또 나간다.
// 기존 BillingEventPersistenceTest는 클래스 단위 @Transactional이라 (a)의 결함(트랜잭션 없는 호출)과 (b)의 flush 시점을
// 가린다. 그래서 NOT_SUPPORTED로 테스트 트랜잭션 감싸기를 꺼 호출마다 실제로 커밋되게 한다.
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("billing_subscriptions.renewal_notice_period_end: 단독 UPDATE와 통째 저장에서의 보존 (트랜잭션 밖 실제 커밋)")
class BillingSubscriptionRenewalNoticePersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BillingSubscriptionRenewalNoticePersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    private static final String SUBSCRIPTION_ID = "sub_notice_1";
    private static final String OTHER_SUBSCRIPTION_ID = "sub_notice_2";
    private static final String CUSTOMER_ID = "ctm_notice_1";
    private static final String PRICE_ID = "pri_notice_1";
    private static final Instant PERIOD_ENDS_AT = Instant.parse("2026-12-10T00:00:00Z");
    private static final Instant NEXT_PERIOD_ENDS_AT = Instant.parse("2027-01-10T00:00:00Z");
    private static final Instant LAST_EVENT_AT = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant NEWER_EVENT_AT = Instant.parse("2026-11-11T03:00:00Z");

    @Autowired
    private BillingSubscriptionRepository billingSubscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO users (provider, provider_user_id, email)
                        VALUES ('github', ?, 'renewal-notice@example.com')
                        RETURNING id
                        """,
                UUID.class,
                UUID.randomUUID().toString());
    }

    @AfterEach
    void cleanUp() {
        // NOT_SUPPORTED라 실제로 커밋된다 — 다음 실행을 위해 직접 지운다 (users 삭제가 구독 캐시도 CASCADE로 지운다)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("(a) 바깥 트랜잭션 없이 호출해도 단독 UPDATE가 성공하고 그 칸만, 그 구독만 바뀐다"
            + " (리포지토리 메서드에 @Transactional이 빠지면 TransactionRequiredException으로 실패해야 한다)")
    void markRenewalNoticeSentSucceedsWithoutOuterTransactionAndChangesOnlyThatColumn() {
        insertSubscription(SUBSCRIPTION_ID, null);
        insertSubscription(OTHER_SUBSCRIPTION_ID, null);
        // 이 테스트가 기대는 전제 — 바깥 트랜잭션이 실제로 없어야 결함이 드러난다
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        billingSubscriptionRepository.markRenewalNoticeSent(SUBSCRIPTION_ID, PERIOD_ENDS_AT);

        assertThat(noticeMark(SUBSCRIPTION_ID)).isEqualTo(PERIOD_ENDS_AT);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, current_period_ends_at, last_event_occurred_at FROM billing_subscriptions"
                        + " WHERE subscription_id = ?", SUBSCRIPTION_ID);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(((Timestamp) row.get("current_period_ends_at")).toInstant()).isEqualTo(PERIOD_ENDS_AT);
        assertThat(((Timestamp) row.get("last_event_occurred_at")).toInstant()).isEqualTo(LAST_EVENT_AT);
        // WHERE가 빠지거나 넓으면 다른 구독의 표시까지 바뀐다
        assertThat(noticeMark(OTHER_SUBSCRIPTION_ID)).isNull();
    }

    @Test
    @DisplayName("(b) 안내 표시가 있는 행을, 표시가 비어 있는 새 BillingSubscription 객체로 save해도 표시가 남는다"
            + " (renewalNoticePeriodEnd에 @Column(updatable = false)가 빠지면 null로 덮여 실패해야 한다)")
    void savingNewObjectWithEmptyNoticeKeepsStoredNoticeMark() {
        insertSubscription(SUBSCRIPTION_ID, PERIOD_ENDS_AT);

        // 웹훅·동기화가 만드는 객체: 상태는 새것이고 안내 표시는 모른다
        billingSubscriptionRepository.save(new BillingSubscription(
                SUBSCRIPTION_ID, userId, CUSTOMER_ID, "canceled", PRICE_ID,
                NEXT_PERIOD_ENDS_AT, "cancel", NEXT_PERIOD_ENDS_AT, NEWER_EVENT_AT, NEWER_EVENT_AT));

        assertThat(noticeMark(SUBSCRIPTION_ID)).isEqualTo(PERIOD_ENDS_AT);
        // 저장 자체는 반영됐다 — 지켜진 것은 표시 한 칸뿐이다
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, current_period_ends_at, last_event_occurred_at FROM billing_subscriptions"
                        + " WHERE subscription_id = ?", SUBSCRIPTION_ID);
        assertThat(row.get("status")).isEqualTo("canceled");
        assertThat(((Timestamp) row.get("current_period_ends_at")).toInstant()).isEqualTo(NEXT_PERIOD_ENDS_AT);
        assertThat(((Timestamp) row.get("last_event_occurred_at")).toInstant()).isEqualTo(NEWER_EVENT_AT);
    }

    private void insertSubscription(String subscriptionId, Instant noticePeriodEnd) {
        jdbcTemplate.update(
                """
                        INSERT INTO billing_subscriptions (
                            subscription_id, user_id, customer_id, status, price_id,
                            current_period_ends_at, last_event_occurred_at, renewal_notice_period_end)
                        VALUES (?, ?, ?, 'active', ?, ?, ?, ?)
                        """,
                subscriptionId, userId, CUSTOMER_ID, PRICE_ID,
                Timestamp.from(PERIOD_ENDS_AT), Timestamp.from(LAST_EVENT_AT),
                noticePeriodEnd == null ? null : Timestamp.from(noticePeriodEnd));
    }

    // 영속성 컨텍스트를 거치지 않고 DB 값을 직접 읽는다
    private Instant noticeMark(String subscriptionId) {
        Timestamp mark = jdbcTemplate.queryForObject(
                "SELECT renewal_notice_period_end FROM billing_subscriptions WHERE subscription_id = ?",
                Timestamp.class, subscriptionId);
        return mark == null ? null : mark.toInstant();
    }
}
