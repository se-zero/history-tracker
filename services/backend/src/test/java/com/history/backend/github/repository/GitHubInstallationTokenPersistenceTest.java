package com.history.backend.github.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.github.domain.GitHubInstallation;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("GitHubInstallationRepository: 설치 토큰 캐시 JPA 퍼시스턴스")
class GitHubInstallationTokenPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GitHubInstallationTokenPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("설치 토큰 저장 후 캐시 조회 성공")
    void saveAndFindInstallationTokenCache() {
        GitHubInstallation installation = createInstallation();
        byte[] encryptedToken = new byte[]{1, 2, 3, 4};
        Instant expiresAt = Instant.parse("2026-05-30T04:00:00Z");

        installation.updateInstallationToken(encryptedToken, expiresAt);
        gitHubInstallationRepository.flush();
        entityManager.clear();

        InstallationTokenCacheView tokenCache = gitHubInstallationRepository.findTokenCacheById(installation.getId())
                .orElseThrow();

        assertThat(tokenCache.getEncryptedInstallationToken()).containsExactly(encryptedToken);
        assertThat(tokenCache.getInstallationTokenExpiresAt()).isEqualTo(expiresAt);
        assertThat(gitHubInstallationRepository.findById(installation.getId()))
                .hasValueSatisfying(savedInstallation -> {
                    assertThat(savedInstallation.getEncryptedInstallationToken()).containsExactly(encryptedToken);
                    assertThat(savedInstallation.getInstallationTokenExpiresAt()).isEqualTo(expiresAt);
                });
    }

    @Test
    @DisplayName("SELECT FOR UPDATE가 트랜잭션 커밋까지 행 잠금 유지")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByIdForUpdateHoldsRowLockUntilTransactionCommit() throws Exception {
        UUID installationId = transactionTemplate().execute(status -> createInstallation().getId());
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<?> locker = executorService.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                gitHubInstallationRepository.findByIdForUpdate(installationId).orElseThrow();
                lockAcquired.countDown();
                await(releaseLock);
            }));

            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> blockedAttempt = executorService.submit(() -> {
                try {
                    transactionTemplate().executeWithoutResult(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '250ms'");
                        gitHubInstallationRepository.findByIdForUpdate(installationId).orElseThrow();
                    });
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });

            Throwable lockFailure = blockedAttempt.get(5, TimeUnit.SECONDS);

            assertThat(lockFailure).isNotNull();
            assertThat(lockFailure).hasStackTraceContaining("lock timeout");

            releaseLock.countDown();
            locker.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executorService.shutdownNow();
        }
    }

    private GitHubInstallation createInstallation() {
        User installer = userRepository.save(new User(
                "github",
                "user-" + System.nanoTime(),
                "owner@example.com",
                "Owner",
                null
        ));
        return gitHubInstallationRepository.saveAndFlush(new GitHubInstallation(
                System.nanoTime(),
                "Organization",
                "acme",
                installer
        ));
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for row lock test.", exception);
        }
    }
}
