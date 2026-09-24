package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.history.backend.auth.service.PlanService;
import com.history.backend.billing.BillingProperties;
import com.history.backend.billing.PaddleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 파기된 사용자(users에 없는 UUID) 알림이 handle()의 트랜잭션 경계를 넘어 롤백되지 않는지 확인한다
// (Mockito 단위 테스트로는 재현 불가 — 실제 @Transactional 프록시·rollback-only 전파가 핵심이다).
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@EnableConfigurationProperties({PaddleProperties.class, BillingProperties.class})
@Import({PaddleWebhookService.class, PlanService.class})
// @DataJpaTest는 테스트 메서드를 트랜잭션으로 감싸고 끝나면 롤백한다. handle()의 @Transactional이
// 그 감싸기에 합류하면 결함이 있어도 최종 커밋 자체가 일어나지 않아 조용히 통과해버린다 — 그래서
// 클래스 레벨에서 감싸기를 꺼(NOT_SUPPORTED) handle()이 자기 트랜잭션을 직접 커밋하게 한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("PaddleWebhookService: 파기된 사용자 알림도 커밋돼야 한다 (noRollbackFor 결함 재현)")
class PaddleWebhookServiceTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaddleWebhookServiceTransactionIntegrationTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    // users에 존재하지 않는 UUID — "파기된 뒤에도 알림은 계속 올 수 있다"는 시나리오를 재현한다
    private static final UUID PURGED_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String EVENT_ID = "evt_purged_user_1";
    private static final String SUBSCRIPTION_ID = "sub_purged_user_1";

    @Autowired
    private PaddleWebhookService paddleWebhookService;

    @MockitoBean
    private PaddleSignatureVerifier verifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // NOT_SUPPORTED라 handle()이 실제로 커밋한다 — 다음 실행을 위해 직접 지운다
        jdbcTemplate.update("DELETE FROM billing_events WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM billing_subscriptions WHERE subscription_id = ?", SUBSCRIPTION_ID);
    }

    @Test
    @DisplayName("파기된 사용자의 subscription.created → 예외 없이 끝나고 커밋돼 billing_events.outcome이 UNMATCHED로 남는다")
    void handleCommitsUnmatchedOutcomeWhenUserIsPurged() {
        when(verifier.verify(any(), any())).thenReturn(true);
        String body = """
                {
                  "event_id": "%s",
                  "event_type": "subscription.created",
                  "occurred_at": "2026-09-24T03:00:00Z",
                  "notification_id": "ntf_purged_1",
                  "data": {
                    "id": "%s",
                    "status": "active",
                    "customer_id": "ctm_purged_1",
                    "custom_data": {"user_id": "%s"},
                    "current_billing_period": {
                      "starts_at": "2026-09-24T00:00:00Z",
                      "ends_at": "2026-10-24T00:00:00Z"
                    },
                    "items": [{"price": {"id": "pri_x", "product_id": "pro_x"}}]
                  }
                }
                """.formatted(EVENT_ID, SUBSCRIPTION_ID, PURGED_USER_ID);

        // noRollbackFor가 없으면 handle()이 UNMATCHED로 잡아 정상 반환하는 것처럼 보여도, 커밋 시점에
        // UnexpectedRollbackException이 handle() 밖으로 던져진다 — 이 assertion이 그 결함을 잡는다.
        assertThatCode(() -> paddleWebhookService.handle("ts=1758682800;h1=deadbeef", body))
                .doesNotThrowAnyException();

        String outcome = jdbcTemplate.queryForObject(
                "SELECT outcome FROM billing_events WHERE event_id = ?", String.class, EVENT_ID);
        assertThat(outcome).isEqualTo("UNMATCHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_subscriptions WHERE subscription_id = ?", Integer.class, SUBSCRIPTION_ID))
                .isZero();
    }
}
