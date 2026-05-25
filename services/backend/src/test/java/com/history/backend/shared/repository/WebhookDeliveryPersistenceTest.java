package com.history.backend.shared.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import com.history.backend.shared.domain.WebhookDelivery;
import com.history.backend.shared.domain.WebhookDeliveryStatus;
import jakarta.persistence.EntityManager;
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
class WebhookDeliveryPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", WebhookDeliveryPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFindClaimedDelivery() {
        Project project = createProject();
        WebhookDelivery delivery = webhookDeliveryRepository.saveAndFlush(WebhookDelivery.claimed(
                "delivery-1",
                project
        ));

        Optional<WebhookDelivery> result = webhookDeliveryRepository.findByDeliveryId("delivery-1");

        assertThat(result).contains(delivery);
        assertThat(result.orElseThrow().getProject()).isEqualTo(project);
        assertThat(result.orElseThrow().getStatus()).isEqualTo(WebhookDeliveryStatus.IN_PROGRESS);
        assertThat(result.orElseThrow().getLastError()).isNull();
        assertThat(result.orElseThrow().getReceivedAt()).isNotNull();
        assertThat(result.orElseThrow().getUpdatedAt()).isNotNull();
        assertThat(webhookDeliveryRepository.existsByDeliveryId("delivery-1")).isTrue();
    }

    @Test
    void tryClaimInsertsOnlyFirstDelivery() {
        Project project = createProject();

        int firstClaim = webhookDeliveryRepository.tryClaim("delivery-2", project.getId());
        int duplicateClaim = webhookDeliveryRepository.tryClaim("delivery-2", project.getId());

        assertThat(firstClaim).isOne();
        assertThat(duplicateClaim).isZero();
        assertThat(webhookDeliveryRepository.findByDeliveryId("delivery-2"))
                .hasValueSatisfying(delivery -> {
                    assertThat(delivery.getProject()).isEqualTo(project);
                    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.IN_PROGRESS);
                });
    }

    @Test
    void tryClaimAllowsMissingProject() {
        int inserted = webhookDeliveryRepository.tryClaim("delivery-3", null);

        assertThat(inserted).isOne();
        assertThat(webhookDeliveryRepository.findByDeliveryId("delivery-3"))
                .hasValueSatisfying(delivery -> assertThat(delivery.getProject()).isNull());
    }

    @Test
    void markProcessedClearsLastErrorAndUpdatesStatus() {
        Project project = createProject();
        WebhookDelivery delivery = webhookDeliveryRepository.saveAndFlush(WebhookDelivery.claimed(
                "delivery-4",
                project
        ));
        delivery.markFailed("temporary failure");
        webhookDeliveryRepository.flush();

        delivery.markProcessed();
        webhookDeliveryRepository.flush();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
        assertThat(delivery.getLastError()).isNull();
        assertStoredStatus(delivery.getDeliveryId(), "PROCESSED");
    }

    @Test
    void markFailedStoresLastError() {
        Project project = createProject();
        WebhookDelivery delivery = webhookDeliveryRepository.saveAndFlush(WebhookDelivery.claimed(
                "delivery-5",
                project
        ));

        delivery.markFailed("collection failed");
        webhookDeliveryRepository.flush();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getLastError()).isEqualTo("collection failed");
        assertStoredStatus(delivery.getDeliveryId(), "FAILED");
    }

    @Test
    void updatingStatusRefreshesUpdatedAt() {
        Project project = createProject();
        WebhookDelivery delivery = webhookDeliveryRepository.saveAndFlush(WebhookDelivery.claimed(
                "delivery-6",
                project
        ));
        Instant before = delivery.getUpdatedAt();

        delivery.markProcessed();
        webhookDeliveryRepository.flush();

        assertThat(delivery.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void deletingProjectCascadesWebhookDelivery() {
        Project project = createProject();
        WebhookDelivery delivery = webhookDeliveryRepository.saveAndFlush(WebhookDelivery.claimed(
                "delivery-7",
                project
        ));

        webhookDeliveryRepository.flush();
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.getId());
        entityManager.clear();

        assertThat(webhookDeliveryRepository.findById(delivery.getId())).isEmpty();
    }

    private Project createProject() {
        User owner = userRepository.save(new User(
                "github",
                "user-" + System.nanoTime(),
                "owner@example.com",
                "Owner",
                null
        ));
        return projectRepository.save(new Project(owner, "History Tracker " + System.nanoTime(), null));
    }

    private void assertStoredStatus(String deliveryId, String status) {
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM webhook_deliveries WHERE delivery_id = ?",
                String.class,
                deliveryId
        );
        assertThat(storedStatus).isEqualTo(status);
    }
}
