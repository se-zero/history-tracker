package com.history.backend.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import com.history.backend.integration.domain.AppCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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
@DisplayName("AppCredentialRepository: 앱 자격증명 JPA 퍼시스턴스")
class AppCredentialPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AppCredentialPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private AppCredentialRepository appCredentialRepository;

    @Test
    @DisplayName("atlassian 자격증명 저장 후 조회 성공")
    void saveAndFindAtlassianCredential() {
        byte[] encryptedCredential = new byte[] {10, 20, 30};
        appCredentialRepository.saveAndFlush(AppCredential.atlassian(encryptedCredential));

        Optional<AppCredential> result = appCredentialRepository.findById("ATLASSIAN");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getEncryptedCredential()).containsExactly(10, 20, 30);
    }

    @Test
    @DisplayName("저장 입력 배열·조회 반환 배열 변조는 내부 값에 영향 없음")
    void getEncryptedCredentialReturnsDefensiveCopyNotInternalReference() {
        byte[] original = new byte[] {1, 2, 3};
        appCredentialRepository.saveAndFlush(AppCredential.atlassian(original));
        original[0] = 99; // 저장에 쓴 입력 배열을 나중에 변조

        AppCredential reloaded = appCredentialRepository.findById("ATLASSIAN").orElseThrow();
        assertThat(reloaded.getEncryptedCredential()).containsExactly(1, 2, 3);

        byte[] returned = reloaded.getEncryptedCredential();
        returned[0] = 42; // getEncryptedCredential() 반환 배열을 변조

        AppCredential reloadedAgain = appCredentialRepository.findById("ATLASSIAN").orElseThrow();
        assertThat(reloadedAgain.getEncryptedCredential()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("저장 시 updatedAt 자동 세팅")
    void updatedAtIsSetOnPersist() {
        AppCredential saved = appCredentialRepository.saveAndFlush(AppCredential.atlassian(new byte[] {1, 2, 3}));

        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateCredential 후 재조회 시 새 값과 갱신된 updatedAt")
    void updateCredentialReplacesValueAndBumpsUpdatedAt() {
        AppCredential saved = appCredentialRepository.saveAndFlush(AppCredential.atlassian(new byte[] {1, 2, 3}));
        Instant firstUpdatedAt = saved.getUpdatedAt();

        saved.updateCredential(new byte[] {9, 9, 9});
        appCredentialRepository.saveAndFlush(saved);

        AppCredential reloaded = appCredentialRepository.findById("ATLASSIAN").orElseThrow();
        assertThat(reloaded.getEncryptedCredential()).containsExactly(9, 9, 9);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(firstUpdatedAt);
    }

    @Test
    @DisplayName("동일 provider 재저장은 행 교체로 동작 (count 유지)")
    void savingSameProviderAgainReplacesRowInsteadOfInserting() {
        appCredentialRepository.saveAndFlush(AppCredential.atlassian(new byte[] {1, 2, 3}));

        AppCredential existing = appCredentialRepository.findById("ATLASSIAN").orElseThrow();
        existing.updateCredential(new byte[] {4, 5, 6});
        appCredentialRepository.saveAndFlush(existing);

        assertThat(appCredentialRepository.count()).isEqualTo(1);
        assertThat(appCredentialRepository.findById("ATLASSIAN").orElseThrow().getEncryptedCredential())
                .containsExactly(4, 5, 6);
    }
}
