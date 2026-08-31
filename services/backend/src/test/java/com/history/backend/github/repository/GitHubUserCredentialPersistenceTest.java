package com.history.backend.github.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.github.domain.GitHubUserCredentialEntity;
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
@DisplayName("GitHubUserCredentialRepository: 사용자 GitHub 토큰 JPA 퍼시스턴스")
class GitHubUserCredentialPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GitHubUserCredentialPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GitHubUserCredentialRepository gitHubUserCredentialRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("저장 후 user_id로 조회하면 encrypted_credential이 비어 있지 않다")
    void saveAndFindByUserIdPersistsEncryptedCredential() {
        User user = createUser("credential-save-user");
        byte[] encryptedCredential = new byte[] {10, 20, 30};

        gitHubUserCredentialRepository.saveAndFlush(
                new GitHubUserCredentialEntity(user.getId(), encryptedCredential));

        GitHubUserCredentialEntity reloaded = gitHubUserCredentialRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getEncryptedCredential()).isNotEmpty().containsExactly(10, 20, 30);
        assertThat(reloaded.getUserId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("같은 user_id로 다시 저장하면 행을 덮어쓰고 count는 1로 유지된다")
    void savingSameUserIdAgainReplacesEncryptedCredential() {
        User user = createUser("credential-upsert-user");
        gitHubUserCredentialRepository.saveAndFlush(
                new GitHubUserCredentialEntity(user.getId(), new byte[] {1, 2, 3}));

        GitHubUserCredentialEntity existing = gitHubUserCredentialRepository.findById(user.getId()).orElseThrow();
        existing.updateCredential(new byte[] {9, 9, 9});
        gitHubUserCredentialRepository.saveAndFlush(existing);

        assertThat(gitHubUserCredentialRepository.count()).isEqualTo(1);
        assertThat(gitHubUserCredentialRepository.findById(user.getId()).orElseThrow().getEncryptedCredential())
                .containsExactly(9, 9, 9);
    }

    @Test
    @DisplayName("users 행 hard delete 후 credential 행이 CASCADE로 사라진다")
    void deletingUserCascadesCredentialRow() {
        User user = createUser("credential-cascade-user");
        gitHubUserCredentialRepository.saveAndFlush(
                new GitHubUserCredentialEntity(user.getId(), new byte[] {1, 2, 3}));

        // ORM 연관이 아니라 DB FK ON DELETE CASCADE를 검증한다. JPA delete는 영속성 컨텍스트
        // 가드에 걸릴 수 있어, 실제 파기 경로처럼 JDBC로 users 행을 지운다.
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM github_user_credentials WHERE user_id = ?",
                Integer.class,
                user.getId());
        assertThat(remaining).isZero();
    }

    private User createUser(String providerUserId) {
        return userRepository.save(new User(
                "github",
                providerUserId,
                providerUserId + "@example.com",
                providerUserId,
                null
        ));
    }
}
