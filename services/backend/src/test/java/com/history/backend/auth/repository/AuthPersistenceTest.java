package com.history.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.history.backend.auth.domain.RefreshToken;
import com.history.backend.auth.domain.User;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.repository.GitHubInstallationRepository;
import org.springframework.data.domain.PageRequest;
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
    private JdbcTemplate jdbcTemplate;

    // 운영 PostgreSQL migration 기준으로 Repository 매핑을 검증한다.
    @Test
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
        assertThat(gitHubInstallationRepository.findAllByInstallerUser_Id(user.getId()))
                .containsExactly(installation);
        assertThat(gitHubInstallationRepository.findByIdAndInstallerUser_Id(installation.getId(), user.getId()))
                .contains(installation);
    }

    // soft-deleted user를 위한 운영 partial unique index를 검증한다.
    @Test
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
                PageRequest.of(0, 100)
        );

        assertThat(candidateIds).contains(expiredDeletedUser.getId());
        assertThat(candidateIds).doesNotContain(recentDeletedUser.getId());
    }
}
