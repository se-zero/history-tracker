package com.history.backend.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.graph.service.AiEngineGraphClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID OTHER_USER_ID = UUID.fromString("0f80f8ae-3fb1-4d90-978e-579a890e9478");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserService userService;

    @Mock
    private AiEngineGraphClient aiEngineGraphClient;

    @Test
    void createProjectSavesProjectForActiveOwner() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        User owner = user(OWNER_ID);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(owner);
        when(projectRepository.existsByOwnerIdAndNameIgnoreCase(OWNER_ID, "History Tracker"))
                .thenReturn(false);
        when(projectRepository.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Project result = service.createProject(OWNER_ID, "History Tracker", "GraphRAG backend");

        assertThat(result.getOwner()).isSameAs(owner);
        assertThat(result.getName()).isEqualTo("History Tracker");
        assertThat(result.getDescription()).isEqualTo("GraphRAG backend");
    }

    @Test
    void createProjectTrimsNameBeforeValidationAndSave() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        User owner = user(OWNER_ID);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(owner);
        when(projectRepository.existsByOwnerIdAndNameIgnoreCase(OWNER_ID, "History Tracker"))
                .thenReturn(false);
        when(projectRepository.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Project result = service.createProject(OWNER_ID, "  History Tracker  ", null);

        assertThat(result.getName()).isEqualTo("History Tracker");
    }

    @Test
    void createProjectRejectsDuplicateActiveNameForOwner() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCase(OWNER_ID, "History Tracker"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createProject(OWNER_ID, "History Tracker", null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project name already exists.");
    }

    @Test
    void createProjectConvertsUniqueConstraintViolationToConflict() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCase(OWNER_ID, "History Tracker"))
                .thenReturn(false);
        when(projectRepository.saveAndFlush(any(Project.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate project name"));

        assertThatThrownBy(() -> service.createProject(OWNER_ID, "History Tracker", null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project name already exists.");
    }

    @Test
    void getProjectRejectsDifferentOwner() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OTHER_USER_ID)).thenReturn(user(OTHER_USER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.getProject(OTHER_USER_ID, PROJECT_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Project access denied.");
    }

    @Test
    void getProjectRejectsMissingProject() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProject(OWNER_ID, PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project not found.");
    }

    @Test
    void updateProjectAllowsSameName() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                OWNER_ID,
                "History Tracker",
                PROJECT_ID
        )).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        Project result = service.updateProject(OWNER_ID, PROJECT_ID, "History Tracker", "Updated");

        assertThat(result.getName()).isEqualTo("History Tracker");
        assertThat(result.getDescription()).isEqualTo("Updated");
    }

    @Test
    void updateProjectChangesDetailsWhenNameIsAvailable() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                OWNER_ID,
                "History Tracker API",
                PROJECT_ID
        )).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        Project result = service.updateProject(OWNER_ID, PROJECT_ID, "History Tracker API", "Backend API");

        assertThat(result.getName()).isEqualTo("History Tracker API");
        assertThat(result.getDescription()).isEqualTo("Backend API");
    }

    @Test
    void updateProjectTrimsNameBeforeValidationAndSave() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                OWNER_ID,
                "History Tracker API",
                PROJECT_ID
        )).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        Project result = service.updateProject(OWNER_ID, PROJECT_ID, "  History Tracker API  ", null);

        assertThat(result.getName()).isEqualTo("History Tracker API");
    }

    @Test
    void updateProjectRejectsDuplicateName() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                OWNER_ID,
                "History Tracker API",
                PROJECT_ID
        )).thenReturn(true);

        assertThatThrownBy(() -> service.updateProject(OWNER_ID, PROJECT_ID, "History Tracker API", null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project name already exists.");
    }

    @Test
    void updateProjectRejectsDifferentOwner() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OTHER_USER_ID)).thenReturn(user(OTHER_USER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.updateProject(OTHER_USER_ID, PROJECT_ID, "History Tracker API", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Project access denied.");
    }

    @Test
    void updateProjectConvertsUniqueConstraintViolationToConflict() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(
                OWNER_ID,
                "History Tracker API",
                PROJECT_ID
        )).thenReturn(false);
        when(projectRepository.saveAndFlush(project))
                .thenThrow(new DataIntegrityViolationException("duplicate project name"));

        assertThatThrownBy(() -> service.updateProject(OWNER_ID, PROJECT_ID, "History Tracker API", null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project name already exists.");
    }

    @Test
    void deleteProjectDeletesGraphBeforeRepository() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.of(project));

        service.deleteProject(OWNER_ID, PROJECT_ID);

        // 그래프 삭제(멱등)가 RDB 삭제보다 먼저 — 그래프 실패 시 RDB 보존돼 재시도로 복구 가능
        InOrder inOrder = inOrder(aiEngineGraphClient, projectRepository);
        inOrder.verify(aiEngineGraphClient).deleteProjectGraph(PROJECT_ID);
        inOrder.verify(projectRepository).deleteById(PROJECT_ID);
    }

    @Test
    void deleteProjectKeepsRepositoryWhenGraphDeleteFails() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.of(project));
        doThrow(new BadGatewayException("Failed to delete project graph."))
                .when(aiEngineGraphClient).deleteProjectGraph(PROJECT_ID);

        assertThatThrownBy(() -> service.deleteProject(OWNER_ID, PROJECT_ID))
                .isInstanceOf(BadGatewayException.class);

        // 그래프 삭제 실패 시 RDB는 건드리지 않음 (재시도 전제 — 고아 데이터 미발생)
        verify(projectRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void deleteProjectRejectsDifferentOwner() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OTHER_USER_ID)).thenReturn(user(OTHER_USER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.deleteProject(OTHER_USER_ID, PROJECT_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Project access denied.");
    }

    @Test
    void deleteProjectRejectsMissingProject() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProject(OWNER_ID, PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project not found.");

        // 미존재면 그래프 삭제도 RDB 삭제도 일어나지 않음
        verify(aiEngineGraphClient, never()).deleteProjectGraph(any(UUID.class));
        verify(projectRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void findProjectsReturnsActiveOwnerProjects() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findAllByOwner_IdOrderByCreatedAtDesc(OWNER_ID))
                .thenReturn(List.of(project));

        List<Project> result = service.findProjects(OWNER_ID);

        assertThat(result).containsExactly(project);
    }

    private User user(UUID id) {
        User user = new User("github", id.toString(), "user@example.com", "User", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Project project(UUID id, UUID ownerId, String name, String description) {
        Project project = new Project(user(ownerId), name, description);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }
}


