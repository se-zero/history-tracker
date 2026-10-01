package com.history.backend.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.billing.domain.BillingSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("billing 패키지: BillingEvent·BillingSubscription JPA 퍼시스턴스")
class BillingEventPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BillingEventPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingEventRepository billingEventRepository;

    @Autowired
    private BillingSubscriptionRepository billingSubscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("첫 번째 claim만 삽입, 같은 event_id 재시도는 0행")
    void tryClaimInsertsOnlyFirstEvent() {
        Instant occurredAt = Instant.parse("2026-09-24T03:00:00Z");
        Instant receivedAt = Instant.parse("2026-09-24T03:00:01Z");

        int firstClaim = billingEventRepository.tryClaim(
                "evt_claim_1", "subscription.created", occurredAt, "ntf_1", receivedAt);
        int duplicateClaim = billingEventRepository.tryClaim(
                "evt_claim_1", "subscription.updated", occurredAt, "ntf_2", receivedAt);

        assertThat(firstClaim).isOne();
        assertThat(duplicateClaim).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT outcome FROM billing_events WHERE event_id = ?", String.class, "evt_claim_1"))
                .isEqualTo("RECEIVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM billing_events WHERE event_id = ?", String.class, "evt_claim_1"))
                .isEqualTo("subscription.created");
        assertThat((Number) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_events WHERE event_id = ?", Long.class, "evt_claim_1"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("updateOutcome이 outcome·subscription_id·user_id를 반영한다")
    void updateOutcomeUpdatesOutcomeAndReferences() {
        User user = createUser();
        Instant occurredAt = Instant.parse("2026-09-24T03:00:00Z");
        billingEventRepository.tryClaim("evt_outcome_1", "subscription.created", occurredAt, "ntf_1", occurredAt);

        int updated = billingEventRepository.updateOutcome(
                "evt_outcome_1", "APPLIED", "sub_outcome_1", user.getId());

        assertThat(updated).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT outcome FROM billing_events WHERE event_id = ?", String.class, "evt_outcome_1"))
                .isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT subscription_id FROM billing_events WHERE event_id = ?", String.class, "evt_outcome_1"))
                .isEqualTo("sub_outcome_1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM billing_events WHERE event_id = ?", UUID.class, "evt_outcome_1"))
                .isEqualTo(user.getId());
    }

    @Test
    @DisplayName("BillingSubscription 저장 후 조회 성공")
    void saveAndFindBillingSubscription() {
        User user = createUser();
        Instant periodEndsAt = Instant.parse("2026-10-24T00:00:00Z");
        Instant lastEventOccurredAt = Instant.parse("2026-09-24T03:00:00Z");
        BillingSubscription subscription = billingSubscriptionRepository.saveAndFlush(new BillingSubscription(
                "sub_save_1",
                user.getId(),
                "ctm_save_1",
                "active",
                "pri_save_1",
                periodEndsAt,
                null,
                null,
                null,
                lastEventOccurredAt
        ));

        Optional<BillingSubscription> found = billingSubscriptionRepository.findById("sub_save_1");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getSubscriptionId()).isEqualTo("sub_save_1");
        assertThat(found.orElseThrow().getUserId()).isEqualTo(user.getId());
        assertThat(found.orElseThrow().getStatus()).isEqualTo("active");
        assertThat(found.orElseThrow().getCurrentPeriodEndsAt()).isEqualTo(periodEndsAt);
        assertThat(found.orElseThrow().getLastEventOccurredAt()).isEqualTo(lastEventOccurredAt);
        assertThat(subscription.getSubscriptionId()).isEqualTo("sub_save_1");
    }

    @Test
    @DisplayName("existsByUserIdAndStatusInAndSubscriptionIdNot: 다른 살아 있는 구독이 있으면 true")
    void existsByUserIdAndStatusInAndSubscriptionIdNotDetectsOtherLiveSubscription() {
        User user = createUser();
        Instant lastEventOccurredAt = Instant.parse("2026-09-24T03:00:00Z");
        billingSubscriptionRepository.saveAndFlush(new BillingSubscription(
                "sub_exists_active", user.getId(), "ctm_1", "active", "pri_1",
                null, null, null, null, lastEventOccurredAt));
        billingSubscriptionRepository.saveAndFlush(new BillingSubscription(
                "sub_exists_canceled", user.getId(), "ctm_2", "canceled", "pri_1",
                null, null, null, null, lastEventOccurredAt));

        boolean existsExcludingCanceled = billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                user.getId(), List.of("active", "trialing", "past_due"), "sub_exists_canceled");

        assertThat(existsExcludingCanceled).isTrue();
    }

    @Test
    @DisplayName("existsByUserIdAndStatusInAndSubscriptionIdNot: 살아 있는 구독이 자기 자신뿐이면 false")
    void existsByUserIdAndStatusInAndSubscriptionIdNotExcludesSelf() {
        User user = createUser();
        Instant lastEventOccurredAt = Instant.parse("2026-09-24T03:00:00Z");
        billingSubscriptionRepository.saveAndFlush(new BillingSubscription(
                "sub_self_only", user.getId(), "ctm_3", "active", "pri_1",
                null, null, null, null, lastEventOccurredAt));

        boolean exists = billingSubscriptionRepository.existsByUserIdAndStatusInAndSubscriptionIdNot(
                user.getId(), List.of("active", "trialing", "past_due"), "sub_self_only");

        assertThat(exists).isFalse();
    }

    private User createUser() {
        return userRepository.save(new User(
                "github",
                "billing-user-" + System.nanoTime(),
                "billing-owner-" + System.nanoTime() + "@example.com",
                "Owner",
                null
        ));
    }
}
