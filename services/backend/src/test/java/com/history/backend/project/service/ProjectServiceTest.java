package com.history.backend.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService: 프로젝트 생성·조회·수정·삭제")
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
    @DisplayName("활성 소유자로 프로젝트 생성 성공")
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
    @DisplayName("프로젝트 이름 앞뒤 공백 제거 후 검증·저장")
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
    @DisplayName("소유자 내 중복 이름으로 프로젝트 생성 거부")
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
    @DisplayName("유니크 제약 위반을 ConflictException으로 변환")
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
    @DisplayName("다른 소유자의 프로젝트 조회 거부")
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
    @DisplayName("존재하지 않는 프로젝트 조회 거부")
    void getProjectRejectsMissingProject() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProject(OWNER_ID, PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project not found.");
    }

    @Test
    @DisplayName("프로젝트 이름 동일로 수정 허용")
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
    @DisplayName("사용 가능한 이름으로 프로젝트 상세 변경")
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
    @DisplayName("수정 시 이름 앞뒤 공백 제거 후 검증·저장")
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
    @DisplayName("중복 이름으로 프로젝트 수정 거부")
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
    @DisplayName("다른 소유자의 프로젝트 수정 거부")
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
    @DisplayName("수정 시 유니크 제약 위반을 ConflictException으로 변환")
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
    @DisplayName("프로젝트 삭제 시 그래프 삭제 후 RDB 삭제")
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
    @DisplayName("그래프 삭제 실패 시 RDB 보존")
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
    @DisplayName("다른 소유자의 프로젝트 삭제 거부")
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
    @DisplayName("존재하지 않는 프로젝트 삭제 거부")
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
    @DisplayName("활성 소유자의 프로젝트 목록 반환")
    void findProjectsReturnsActiveOwnerProjects() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));

        List<Project> result = service.findProjects(OWNER_ID);

        assertThat(result).containsExactly(project);
    }

    @Test
    @DisplayName("드래그 순서대로 sortOrder 재채번 후 정렬된 목록 반환")
    void reorderProjectsAssignsSortOrderByRequestedSequence() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        UUID idA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID idC = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Project a = project(idA, OWNER_ID, "A", null);
        Project b = project(idB, OWNER_ID, "B", null);
        Project c = project(idC, OWNER_ID, "C", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        // 저장소는 항상 sortOrder 오름차순으로 준다고 가정
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(new java.util.ArrayList<>(List.of(a, b, c)));

        List<Project> result = service.reorderProjects(OWNER_ID, List.of(idC, idA, idB));

        assertThat(result).containsExactly(c, a, b);
        assertThat(c.getSortOrder()).isEqualTo(0);
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).isEqualTo(2);
        verify(projectRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("타 사용자·미존재 id 포함 순서 변경 거부")
    void reorderProjectsRejectsUnknownId() {
        ProjectService service = new ProjectService(projectRepository, userService, aiEngineGraphClient);
        UUID idA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID unknown = UUID.fromString("99999999-9999-9999-9999-999999999999");
        Project a = project(idA, OWNER_ID, "A", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(new java.util.ArrayList<>(List.of(a)));

        assertThatThrownBy(() -> service.reorderProjects(OWNER_ID, List.of(idA, unknown)))
                .isInstanceOf(NotFoundException.class);

        verify(projectRepository, never()).saveAll(anyList());
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


