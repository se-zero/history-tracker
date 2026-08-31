package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.service.GitHubAppClient;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.GitHubOAuthClient;
import com.history.backend.security.JwtProperties;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Import({
        AuthService.class,
        RefreshTokenService.class,
        JwtTokenService.class,
        RefreshTokenServicePersistenceTest.JwtConfig.class
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("RefreshTokenService: 재사용 탐지 폐기의 트랜잭션 커밋")
class RefreshTokenServicePersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RefreshTokenServicePersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @TestConfiguration
    static class JwtConfig {
        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties("test-secret", Duration.ofMinutes(15), Duration.ofDays(14));
        }

        @Bean
        GitHubAppProperties gitHubAppProperties() {
            return new GitHubAppProperties(
                    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""
            );
        }
    }

    @MockitoBean
    private GitHubOAuthClient gitHubOAuthClient;

    @MockitoBean
    private GitHubAppClient gitHubAppClient;

    @MockitoBean
    private GitHubInstallationService gitHubInstallationService;

    @MockitoBean
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("AuthService.refresh 경로에서도 유예 지난 재사용은 전 세션 폐기가 커밋된다")
    void authServiceRefreshCommitsRevokeAllWhenReusedAfterGrace() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID userId = tx.execute(status -> userRepository.save(new User(
                "github",
                "reuse-commit",
                "reuse-commit@example.com",
                "Reuse",
                null
        )).getId());

        String firstRaw = tx.execute(status -> refreshTokenService.issueRefreshToken(
                userRepository.findById(userId).orElseThrow()
        ));
        tx.executeWithoutResult(status -> refreshTokenService.rotateRefreshToken(firstRaw));

        jdbcTemplate.update(
                "UPDATE refresh_tokens SET replaced_at = ? WHERE user_id = ? AND replaced_at IS NOT NULL",
                Timestamp.from(Instant.now().minus(RefreshTokenService.REUSE_GRACE).minusSeconds(1)),
                userId
        );

        assertThatThrownBy(() -> authService.refresh(firstRaw))
                .isInstanceOf(UnauthorizedException.class);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?",
                Integer.class,
                userId
        );
        assertThat(remaining).isZero();
    }
}
