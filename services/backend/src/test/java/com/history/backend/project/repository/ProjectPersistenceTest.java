package com.history.backend.project.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.project.domain.Project;
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
@DisplayName("ProjectRepository: 프로젝트 JPA 퍼시스턴스")
class ProjectPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ProjectPersistenceTest::postgresJdbcUrl);
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

    @Test
    @DisplayName("프로젝트 저장 후 조회 성공")
    void saveAndfindProject() {
        User owner = userRepository.save(new User("github", "2001", "owner@example.com", "Owner", null));
        Project project = projectRepository.save(new Project(owner, "History Tracker", "GraphRAG backend"));

        assertThat(projectRepository.findById(project.getId())).contains(project);
        assertThat(projectRepository.findAllByOwner_IdOrderByCreatedAtDesc(owner.getId()))
                .containsExactly(project);
        assertThat(projectRepository.existsByOwnerIdAndNameIgnoreCase(
                owner.getId(),
                "history tracker"
        )).isTrue();
    }

    @Test
    @DisplayName("프로젝트 삭제 후 조회 불가")
    void deleteRemovesProject() {
        User owner = userRepository.save(new User("github", "2002", "owner2@example.com", "Owner", null));
        Project project = projectRepository.saveAndFlush(new Project(owner, "History Tracker", null));
        projectRepository.delete(project);
        projectRepository.flush();

        assertThat(projectRepository.findById(project.getId())).isEmpty();
        assertThat(projectRepository.findAllByOwner_IdOrderByCreatedAtDesc(owner.getId()))
                .isEmpty();
        assertThat(projectRepository.existsByOwnerIdAndNameIgnoreCase(
                owner.getId(),
                "History Tracker"
        )).isFalse();
    }

    @Test
    @DisplayName("현재 프로젝트 제외 이름 중복 검사 → 자기 자신 제외")
    void duplicateNameCheckCanExcludeCurrentProject() {
        User owner = userRepository.save(new User("github", "2003", "owner3@example.com", "Owner", null));
        Project project = projectRepository.save(new Project(owner, "History Tracker", null));

        assertThat(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                owner.getId(),
                "history tracker",
                project.getId()
        )).isFalse();
    }

    @Test
    @DisplayName("다른 프로젝트와 이름 중복 검사 → 중복 감지")
    void duplicateNameCheckFindsOtherActiveProject() {
        User owner = userRepository.save(new User("github", "2004", "owner4@example.com", "Owner", null));
        Project currentProject = projectRepository.save(new Project(owner, "History Tracker", null));
        projectRepository.save(new Project(owner, "Backend API", null));

        assertThat(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                owner.getId(),
                "backend api",
                currentProject.getId()
        )).isTrue();
    }
}

