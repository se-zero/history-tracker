package com.history.backend.mcp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.mcp.domain.McpWorkspaceBinding;
import com.history.backend.mcp.domain.McpWorkspaceBindingId;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("McpWorkspaceBindingRepository: 작업 폴더 연결 JPA 퍼시스턴스")
class McpWorkspaceBindingPersistenceTest {

    private static final String PATH = "/home/me/repo";
    private static final String OTHER_PATH = "/home/me/other-repo";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", McpWorkspaceBindingPersistenceTest::postgresJdbcUrl);
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
    private McpWorkspaceBindingRepository bindingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("연결 저장 후 복합키로 조회 성공")
    void saveAndFindBinding() {
        User user = createUser();
        Project project = createProject(user);

        bindingRepository.saveAndFlush(new McpWorkspaceBinding(user.getId(), PATH, project.getId()));
        entityManager.clear();

        McpWorkspaceBinding found = bindingRepository.findById(id(user, PATH)).orElseThrow();
        assertThat(found.getId()).isEqualTo(id(user, PATH));
        assertThat(found.getProjectId()).isEqualTo(project.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 사용자의 다른 경로는 별개 행")
    void sameUserDifferentPathsAreSeparateRows() {
        User user = createUser();
        Project first = createProject(user);
        Project second = createProject(user);

        bindingRepository.save(new McpWorkspaceBinding(user.getId(), PATH, first.getId()));
        bindingRepository.save(new McpWorkspaceBinding(user.getId(), OTHER_PATH, second.getId()));
        bindingRepository.flush();
        entityManager.clear();

        assertThat(bindingRepository.findById(id(user, PATH)).orElseThrow().getProjectId())
                .isEqualTo(first.getId());
        assertThat(bindingRepository.findById(id(user, OTHER_PATH)).orElseThrow().getProjectId())
                .isEqualTo(second.getId());
        assertThat(countBindings()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 경로의 다른 사용자는 별개 행")
    void samePathDifferentUsersAreSeparateRows() {
        User user = createUser();
        User otherUser = createUser();
        Project project = createProject(user);
        Project otherProject = createProject(otherUser);

        bindingRepository.save(new McpWorkspaceBinding(user.getId(), PATH, project.getId()));
        bindingRepository.save(new McpWorkspaceBinding(otherUser.getId(), PATH, otherProject.getId()));
        bindingRepository.flush();
        entityManager.clear();

        assertThat(bindingRepository.findById(id(user, PATH)).orElseThrow().getProjectId())
                .isEqualTo(project.getId());
        assertThat(bindingRepository.findById(id(otherUser, PATH)).orElseThrow().getProjectId())
                .isEqualTo(otherProject.getId());
        assertThat(countBindings()).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 행 삭제 시 연결 cascade 삭제")
    void deletingUserCascadesBindings() {
        User user = createUser();
        User otherUser = createUser();
        Project project = createProject(user);
        Project otherProject = createProject(otherUser);
        bindingRepository.save(new McpWorkspaceBinding(user.getId(), PATH, project.getId()));
        bindingRepository.saveAndFlush(new McpWorkspaceBinding(otherUser.getId(), PATH, otherProject.getId()));

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
        entityManager.clear();

        assertThat(bindingRepository.findById(id(user, PATH))).isEmpty();
        assertThat(bindingRepository.findById(id(otherUser, PATH))).isPresent();
    }

    @Test
    @DisplayName("프로젝트 행 삭제 시 연결 cascade 삭제")
    void deletingProjectCascadesBindings() {
        User user = createUser();
        Project project = createProject(user);
        Project otherProject = createProject(user);
        bindingRepository.save(new McpWorkspaceBinding(user.getId(), PATH, project.getId()));
        bindingRepository.saveAndFlush(new McpWorkspaceBinding(user.getId(), OTHER_PATH, otherProject.getId()));

        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.getId());
        entityManager.clear();

        assertThat(bindingRepository.findById(id(user, PATH))).isEmpty();
        assertThat(bindingRepository.findById(id(user, OTHER_PATH))).isPresent();
    }

    @Test
    @DisplayName("1024바이트 경로도 저장 가능")
    void savesMaxLengthPath() {
        User user = createUser();
        Project project = createProject(user);
        String longPath = "/" + "a".repeat(1023);

        bindingRepository.saveAndFlush(new McpWorkspaceBinding(user.getId(), longPath, project.getId()));
        entityManager.clear();

        assertThat(bindingRepository.findById(id(user, longPath))).isPresent();
    }

    // 같은 글자의 반복은 인덱스에서 압축돼 한도를 가늠하지 못한다 — 서로 다른 한글(3바이트)로 상한을 채워
    // WorkspacePathNormalizer의 1024바이트 상한이 PK 인덱스 한도 안쪽임을 실제 Postgres로 확인한다.
    @Test
    @DisplayName("압축되지 않는 한글 1024바이트 경로도 PK 인덱스에 들어간다")
    void savesIncompressibleMultibyteMaxLengthPath() {
        User user = createUser();
        Project project = createProject(user);
        StringBuilder builder = new StringBuilder("/");
        for (int i = 0; i < 341; i++) {
            builder.append((char) (0xAC00 + (i * 7919) % 11172));
        }
        String longPath = builder.toString();

        bindingRepository.saveAndFlush(new McpWorkspaceBinding(user.getId(), longPath, project.getId()));
        entityManager.clear();

        assertThat(bindingRepository.findById(id(user, longPath))).isPresent();
    }

    @Test
    @DisplayName("프로젝트 교체 시 project_id와 updatedAt이 갱신되고 createdAt은 유지")
    void changeProjectUpdatesProjectAndUpdatedAt() throws InterruptedException {
        User user = createUser();
        Project first = createProject(user);
        Project second = createProject(user);
        bindingRepository.saveAndFlush(new McpWorkspaceBinding(user.getId(), PATH, first.getId()));
        entityManager.clear();
        McpWorkspaceBinding loaded = bindingRepository.findById(id(user, PATH)).orElseThrow();
        Instant createdAt = loaded.getCreatedAt();
        Instant updatedAtBefore = loaded.getUpdatedAt();

        Thread.sleep(20);
        loaded.changeProject(second.getId());
        bindingRepository.flush();
        entityManager.clear();

        McpWorkspaceBinding reloaded = bindingRepository.findById(id(user, PATH)).orElseThrow();
        assertThat(reloaded.getProjectId()).isEqualTo(second.getId());
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfter(updatedAtBefore);
        assertThat(countBindings()).isEqualTo(1);
    }

    private McpWorkspaceBindingId id(User user, String path) {
        return new McpWorkspaceBindingId(user.getId(), path);
    }

    private int countBindings() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mcp_workspace_bindings", Integer.class);
    }

    private User createUser() {
        return userRepository.save(new User(
                "github",
                "user-" + UUID.randomUUID(),
                "owner@example.com",
                "Owner",
                null
        ));
    }

    private Project createProject(User owner) {
        return projectRepository.saveAndFlush(new Project(owner, "History Tracker " + UUID.randomUUID(), null));
    }
}
