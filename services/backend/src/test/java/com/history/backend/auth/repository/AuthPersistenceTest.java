package com.history.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.RefreshToken;
import com.history.backend.auth.domain.User;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.repository.GitHubInstallationRepository;
import org.springframework.data.domain.PageRequest;
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
@DisplayName("UserRepository/RefreshTokenRepository: 인증 JPA 퍼시스턴스")
class AuthPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AuthPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Autowired
    private UserProviderConnectionRepository userProviderConnectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // 운영 PostgreSQL migration 기준으로 Repository 매핑을 검증한다.
    @Test
    @DisplayName("User/RefreshToken/GitHubInstallation 저장 후 조회 성공")
    void saveAndFindAuthFoundationEntities() {
        User user = userRepository.save(new User(
                "github",
                "12345",
                "octocat@example.com",
                "Octocat",
                "https://github.com/images/error/octocat_happy.gif"
        ));

        RefreshToken refreshToken = refreshTokenRepository.save(new RefreshToken(
                user,
                new byte[]{1, 2, 3},
                Instant.now().plusSeconds(3600)
        ));

        GitHubInstallation installation = gitHubInstallationRepository.save(new GitHubInstallation(
                98765L,
                "Organization",
                "acme",
                user
        ));

        assertThat(userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("github", "12345"))
                .contains(user);
        assertThat(refreshTokenRepository.findByTokenHash(new byte[]{1, 2, 3}))
                .contains(refreshToken);
        assertThat(gitHubInstallationRepository.findByInstallationId(98765L))
                .contains(installation);
    }

    // soft-deleted user를 위한 운영 partial unique index를 검증한다.
    @Test
    @DisplayName("soft delete 후 동일 OAuth ID로 재가입 가능")
    void allowSameOAuthIdentityAfterSoftDelete() {
        User deletedUser = userRepository.saveAndFlush(new User(
                "github",
                "12345",
                "octocat@example.com",
                "Octocat",
                null
        ));
        deletedUser.softDelete(Instant.now());
        userRepository.saveAndFlush(deletedUser);

        User newUser = userRepository.saveAndFlush(new User(
                "github",
                "12345",
                "octocat@example.com",
                "Octocat",
                null
        ));

        assertThat(newUser.getId()).isNotEqualTo(deletedUser.getId());
    }

    // 운영 CITEXT email 비교 규칙을 검증한다.
    @Test
    @DisplayName("email 비교는 대소문자 무시 CITEXT 사용")
    void emailUsesCaseInsensitiveCitextComparison() {
        userRepository.saveAndFlush(new User(
                "github",
                "12345",
                "Octocat@Example.com",
                "Octocat",
                null
        ));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class,
                "octocat@example.com"
        );

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("퍼지 대상 조회 시 만료된 soft-deleted 사용자만 반환")
    void findPurgeCandidateIdsReturnsExpiredDeletedUsersOnly() {
        User expiredDeletedUser = userRepository.save(new User(
                "github",
                "expired",
                "expired@example.com",
                "Expired",
                null
        ));
        expiredDeletedUser.softDelete(Instant.now().minusSeconds(31L * 24 * 60 * 60));
        User recentDeletedUser = userRepository.save(new User(
                "github",
                "recent",
                "recent@example.com",
                "Recent",
                null
        ));
        recentDeletedUser.softDelete(Instant.now().minusSeconds(29L * 24 * 60 * 60));
        userRepository.save(new User(
                "github",
                "active",
                "active@example.com",
                "Active",
                null
        ));
        userRepository.flush();

        List<java.util.UUID> candidateIds = userRepository.findPurgeCandidateIds(
                Instant.now().minusSeconds(30L * 24 * 60 * 60),
                List.of(),
                PageRequest.of(0, 100)
        );

        assertThat(candidateIds).contains(expiredDeletedUser.getId());
        assertThat(candidateIds).doesNotContain(recentDeletedUser.getId());
    }

    @Test
    @DisplayName("users 테이블 deleted_at 퍼지 인덱스 존재")
    void usersDeletedAtPurgeIndexExists() {
        String indexDefinition = jdbcTemplate.queryForObject(
                """
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'users'
                          AND indexname = 'idx_users_deleted_at_purge'
                        """,
                String.class
        );

        assertThat(indexDefinition).contains("deleted_at");
        assertThat(indexDefinition).contains("WHERE (deleted_at IS NOT NULL)");
    }

    @Test
    @DisplayName("FREE 질의 카운트는 한도 미만에서만 원자적으로 증가하고 한도에 닿으면 0건")
    void incrementFreeQueryCountIfBelowLimitStopsAtLimitForFreeUser() {
        User user = userRepository.saveAndFlush(new User(
                "github",
                "query-limit-free",
                "query-limit-free@example.com",
                "Free",
                null
        ));

        int limit = 10;
        for (int i = 0; i < limit; i++) {
            assertThat(userRepository.incrementFreeQueryCountIfBelowLimit(user.getId(), limit))
                    .isEqualTo(1);
        }

        assertThat(userRepository.incrementFreeQueryCountIfBelowLimit(user.getId(), limit)).isZero();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getFreeQueryCount())
                .isEqualTo(limit);
    }

    @Test
    @DisplayName("PAID 사용자는 원자적 증가 대상이 아니라 0건, 카운트도 그대로")
    void incrementFreeQueryCountIfBelowLimitDoesNotTouchPaidUser() {
        User user = new User(
                "github",
                "query-limit-paid",
                "query-limit-paid@example.com",
                "Paid",
                null
        );
        user.upgradeToPaid();
        user = userRepository.saveAndFlush(user);

        assertThat(userRepository.incrementFreeQueryCountIfBelowLimit(user.getId(), 10)).isZero();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getFreeQueryCount()).isZero();
        assertThat(user.getPlan()).isEqualTo(Plan.PAID);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동시 원자적 증가도 한도를 넘기지 않는다")
    void incrementFreeQueryCountIfBelowLimitConcurrentCallsDoNotExceedLimit() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        User user = transactionTemplate.execute(status -> userRepository.save(new User(
                "github",
                "query-limit-concurrent",
                "query-limit-concurrent@example.com",
                "Concurrent",
                null
        )));

        int workers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    transactionTemplate.executeWithoutResult(status -> {
                        int updated = userRepository.incrementFreeQueryCountIfBelowLimit(user.getId(), 10);
                        if (updated == 1) {
                            successes.incrementAndGet();
                        }
                    });
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT free_query_count FROM users WHERE id = ?",
                Integer.class,
                user.getId()
        );
        assertThat(successes.get()).isEqualTo(10);
        assertThat(count).isEqualTo(10);
    }

    @Test
    @DisplayName("provider 연동 이력 insert는 같은 (user, provider)를 두 번 넣어도 예외 없이 한 행만 남긴다")
    void insertIfAbsentIsIdempotentForSameUserAndProvider() {
        User user = userRepository.saveAndFlush(new User(
                "github",
                "connection-idempotent",
                "connection-idempotent@example.com",
                "Idempotent",
                null
        ));

        userProviderConnectionRepository.insertIfAbsent(user.getId(), "github");
        userProviderConnectionRepository.insertIfAbsent(user.getId(), "github");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_provider_connections WHERE user_id = ? AND provider = ?",
                Integer.class,
                user.getId(),
                "github"
        );
        assertThat(count).isEqualTo(1);
    }
}
