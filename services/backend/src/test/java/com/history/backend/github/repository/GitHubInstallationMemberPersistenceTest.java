package com.history.backend.github.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.github.domain.GitHubInstallation;
import java.util.UUID;
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

// github_installation_users 조인 테이블 — installer_user_id 단독 소유였던 GitHub App 설치 접근권을
// 여러 사용자가 공유하도록 분리한 멤버십 퍼시스턴스를 검증한다.
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("GitHubInstallationMemberRepository: 설치 접근권 공유 JPA 퍼시스턴스")
class GitHubInstallationMemberPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GitHubInstallationMemberPersistenceTest::postgresJdbcUrl);
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
    private GitHubInstallationMemberRepository gitHubInstallationMemberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("멤버 등록 후 설치·사용자 기준 조회가 멤버로 등록된 사용자만 반환")
    void memberLookupsReturnInstallationOnlyForRegisteredMembers() {
        User installer = createUser("member-lookup-installer");
        User teammate = createUser("member-lookup-teammate");
        User outsider = createUser("member-lookup-outsider");
        GitHubInstallation installation = createInstallation(installer);

        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());
        gitHubInstallationMemberRepository.addMember(installation.getId(), teammate.getId());

        assertThat(gitHubInstallationRepository.findByIdAndMemberUserId(installation.getId(), teammate.getId()))
                .contains(installation);
        assertThat(gitHubInstallationRepository.findAllByMemberUserId(teammate.getId()))
                .containsExactly(installation);
        // 등록되지 않은 사용자는 같은 설치라도 접근권이 없다
        assertThat(gitHubInstallationRepository.findByIdAndMemberUserId(installation.getId(), outsider.getId()))
                .isEmpty();
        assertThat(gitHubInstallationRepository.findAllByMemberUserId(outsider.getId())).isEmpty();
    }

    @Test
    @DisplayName("같은 (installation, user) 조합을 두 번 등록해도 예외 없이 멱등")
    void addMemberIsIdempotent() {
        User installer = createUser("idempotent-installer");
        GitHubInstallation installation = createInstallation(installer);

        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());
        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());

        assertThat(memberRowCount(installation.getId(), installer.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("설치자 사용자를 지워도 설치 행은 살아남고 installer_user_id만 null이 된다 — ON DELETE SET NULL")
    void deletingInstallerUserKeepsInstallationRowButNullsInstallerColumn() {
        User installer = createUser("set-null-installer");
        GitHubInstallation installation = createInstallation(installer);
        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());

        // 여기서 검증하려는 건 DB의 ON DELETE SET NULL 동작이지 ORM 동작이 아니다. JPA로 삭제하면
        // 영속성 컨텍스트에 이미 올라온 installation.installerUser가 삭제 예정 엔티티를 참조한다고
        // Hibernate가 flush 시점에 거부한다(TransientPropertyValueException, CASCADE만 예외 처리됨).
        // 실제 파기 경로인 UserPurgeService는 entityManager를 거치지 않는 벌크 삭제
        // (deleteAllByIdInBatch)라 이 가드를 타지 않으므로, jdbcTemplate으로 세션을 우회해 운영
        // 경로와 같은 조건에서 FK 동작만 확인한다.
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", installer.getId());

        Integer remainingInstallations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM github_installations WHERE id = ?",
                Integer.class,
                installation.getId());
        assertThat(remainingInstallations).isEqualTo(1);
        Integer nullInstallerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM github_installations WHERE id = ? AND installer_user_id IS NULL",
                Integer.class,
                installation.getId());
        assertThat(nullInstallerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("멤버 사용자를 지우면 멤버십 행만 CASCADE로 사라지고 설치 행은 남는다")
    void deletingMemberUserCascadesOnlyMembershipRow() {
        User installer = createUser("member-delete-installer");
        User teammate = createUser("member-delete-teammate");
        GitHubInstallation installation = createInstallation(installer);
        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());
        gitHubInstallationMemberRepository.addMember(installation.getId(), teammate.getId());

        userRepository.delete(teammate);
        userRepository.flush();

        assertThat(gitHubInstallationRepository.findById(installation.getId())).isPresent();
        assertThat(memberRowCount(installation.getId(), teammate.getId())).isZero();
        // 지운 사용자의 멤버십만 사라지고 나머지 멤버는 남는다
        assertThat(memberRowCount(installation.getId(), installer.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("설치 행을 지우면 멤버십 행이 CASCADE로 사라진다")
    void deletingInstallationCascadesMembershipRows() {
        User installer = createUser("installation-delete-installer");
        User teammate = createUser("installation-delete-teammate");
        GitHubInstallation installation = createInstallation(installer);
        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());
        gitHubInstallationMemberRepository.addMember(installation.getId(), teammate.getId());

        gitHubInstallationRepository.delete(installation);
        gitHubInstallationRepository.flush();

        Integer remainingMemberships = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM github_installation_users WHERE installation_id = ?",
                Integer.class,
                installation.getId());
        assertThat(remainingMemberships).isZero();
        // 사용자 행 자체는 설치 삭제와 무관하게 남는다
        assertThat(userRepository.findById(installer.getId())).isPresent();
        assertThat(userRepository.findById(teammate.getId())).isPresent();
    }

    private Integer memberRowCount(UUID installationId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM github_installation_users WHERE installation_id = ? AND user_id = ?",
                Integer.class,
                installationId,
                userId);
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

    private GitHubInstallation createInstallation(User installer) {
        return gitHubInstallationRepository.saveAndFlush(new GitHubInstallation(
                System.nanoTime(),
                "Organization",
                "acme",
                installer
        ));
    }
}
