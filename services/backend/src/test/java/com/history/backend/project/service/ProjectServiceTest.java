package com.history.backend.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.history.backend.integration.service.IntegrationRevocationService;
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
    private static final UUID FIRST_OWNED_PROJECT_ID = UUID.fromString("266acdfb-5dfd-4d26-8808-a92eb4f983ee");
    private static final UUID SECOND_OWNED_PROJECT_ID = UUID.fromString("aa88745d-b66b-4569-a949-5e5948c2df20");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserService userService;

    @Mock
    private AiEngineGraphClient aiEngineGraphClient;

    @Mock
    private IntegrationRevocationService integrationRevocationService;

    @Test
    @DisplayName("활성 소유자로 프로젝트 생성 성공")
    void createProjectSavesProjectForActiveOwner() {
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProject(OWNER_ID, PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project not found.");
    }

    @Test
    @DisplayName("프로젝트 이름 동일로 수정 허용")
    void updateProjectAllowsSameName() {
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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
    @DisplayName("프로젝트 삭제 시 연동 권한 폐기 → 그래프 삭제 → RDB 삭제 순서 — RDB 행이 지워지면 폐기에 쓸 자격증명이 사라진다")
    void deleteProjectRevokesIntegrationsBeforeGraphBeforeRepository() {
        ProjectService service = service();
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.of(project));

        service.deleteProject(OWNER_ID, PROJECT_ID);

        // 연동 폐기 → 그래프 삭제(멱등) → RDB 삭제 순서. RDB를 먼저 지우면 폐기에 쓸 자격증명(암호화된
        // 토큰)이 사라지고, 그래프를 RDB보다 먼저 지우는 이유는 기존과 동일(그래프 실패 시 RDB 보존돼
        // 재시도로 복구 가능).
        InOrder inOrder = inOrder(integrationRevocationService, aiEngineGraphClient, projectRepository);
        inOrder.verify(integrationRevocationService).revokeAll(PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).deleteProjectGraph(PROJECT_ID);
        inOrder.verify(projectRepository).deleteById(PROJECT_ID);
    }

    @Test
    @DisplayName("그래프 삭제 실패 시 RDB 보존")
    void deleteProjectKeepsRepositoryWhenGraphDeleteFails() {
        ProjectService service = service();
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
    @DisplayName("사용자 대면 삭제는 연동 권한 폐기가 실패해도 예외를 던지지 않고 그래프·RDB 삭제를 계속 진행한다"
            + " — releaseExternalResources(파기 전용)와의 비대칭이 의도된 설계다")
    void deleteProjectContinuesGraphAndRepositoryDeleteWhenIntegrationRevocationFails() {
        ProjectService service = service();
        Project project = project(PROJECT_ID, OWNER_ID, "History Tracker", null);
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.of(project));
        when(integrationRevocationService.revokeAll(PROJECT_ID)).thenReturn(false);

        service.deleteProject(OWNER_ID, PROJECT_ID);

        // 파기 전용 releaseExternalResources와 달리, 사용자가 직접 요청한 삭제는 provider 장애로
        // 프로젝트를 못 지우게 막으면 안 된다 — 폐기 실패에도 그래프·RDB 삭제가 이어져야 한다
        verify(aiEngineGraphClient).deleteProjectGraph(PROJECT_ID);
        verify(projectRepository).deleteById(PROJECT_ID);
    }

    @Test
    @DisplayName("다른 소유자의 프로젝트 삭제 거부")
    void deleteProjectRejectsDifferentOwner() {
        ProjectService service = service();
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
        ProjectService service = service();
        when(userService.getActiveUser(OWNER_ID)).thenReturn(user(OWNER_ID));
        when(projectRepository.findByIdWithOwner(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProject(OWNER_ID, PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project not found.");

        // 미존재면 연동 폐기도 그래프 삭제도 RDB 삭제도 일어나지 않음
        verify(integrationRevocationService, never()).revokeAll(any(UUID.class));
        verify(aiEngineGraphClient, never()).deleteProjectGraph(any(UUID.class));
        verify(projectRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("활성 소유자의 프로젝트 목록 반환")
    void findProjectsReturnsActiveOwnerProjects() {
        ProjectService service = service();
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
        ProjectService service = service();
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
        ProjectService service = service();
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

    @Test
    @DisplayName("파기: 소유 프로젝트마다 연동 권한 폐기 → 그래프 삭제 순서로 자원 정리")
    void releaseExternalResourcesRevokesThenDeletesGraphForEachOwnedProjectInOrder() {
        ProjectService service = service();
        Project first = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        Project second = project(SECOND_OWNED_PROJECT_ID, OWNER_ID, "Second", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(first, second));
        // 성공 경로다 — 스텁하지 않으면 mock의 boolean 기본값(false)이 "폐기 실패"로 읽혀
        // releaseExternalResources가 BadGatewayException을 던진다.
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(true);
        when(integrationRevocationService.revokeAll(SECOND_OWNED_PROJECT_ID)).thenReturn(true);

        service.releaseExternalResources(OWNER_ID);

        InOrder inOrder = inOrder(integrationRevocationService, aiEngineGraphClient);
        inOrder.verify(integrationRevocationService).revokeAll(FIRST_OWNED_PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).deleteProjectGraph(FIRST_OWNED_PROJECT_ID);
        inOrder.verify(integrationRevocationService).revokeAll(SECOND_OWNED_PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).deleteProjectGraph(SECOND_OWNED_PROJECT_ID);
    }

    @Test
    @DisplayName("파기: RDB 삭제는 사용자 행 삭제의 CASCADE가 담당하므로 프로젝트 행을 직접 지우지 않는다")
    void releaseExternalResourcesDoesNotDeleteProjectRepositoryRows() {
        ProjectService service = service();
        Project project = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(true);

        service.releaseExternalResources(OWNER_ID);

        verify(projectRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("파기: soft-deleted 대상이라 activeUser 검증을 타지 않는다")
    void releaseExternalResourcesDoesNotValidateActiveUser() {
        ProjectService service = service();
        Project project = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(true);

        service.releaseExternalResources(OWNER_ID);

        verify(userService, never()).getActiveUser(any(UUID.class));
    }

    @Test
    @DisplayName("파기: 그래프 삭제 실패는 전파돼 호출부가 해당 사용자를 건너뛸 수 있다")
    void releaseExternalResourcesPropagatesGraphDeleteFailure() {
        ProjectService service = service();
        Project project = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));
        // 폐기는 성공시켜야 그래프 삭제 단계까지 도달한다 — 이 테스트가 보려는 건 그래프 실패다.
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(true);
        doThrow(new BadGatewayException("Failed to delete project graph."))
                .when(aiEngineGraphClient).deleteProjectGraph(FIRST_OWNED_PROJECT_ID);

        assertThatThrownBy(() -> service.releaseExternalResources(OWNER_ID))
                .isInstanceOf(BadGatewayException.class);
    }

    @Test
    @DisplayName("파기: 연동 권한 폐기가 실패하면 BadGatewayException을 던지고 그래프 삭제로 넘어가지 않는다")
    void releaseExternalResourcesThrowsBadGatewayExceptionWhenIntegrationRevocationFails() {
        ProjectService service = service();
        Project project = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.releaseExternalResources(OWNER_ID))
                .isInstanceOf(BadGatewayException.class);

        // 그래프 삭제 실패와 동일하게 취급 — 폐기가 실패한 프로젝트의 그래프는 건드리지 않고 중단한다
        verify(aiEngineGraphClient, never()).deleteProjectGraph(any(UUID.class));
    }

    @Test
    @DisplayName("파기: 소유 프로젝트가 없으면 아무 것도 호출하지 않고 종료")
    void releaseExternalResourcesDoesNothingWhenOwnerHasNoProjects() {
        ProjectService service = service();
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of());

        service.releaseExternalResources(OWNER_ID);

        verifyNoInteractions(integrationRevocationService, aiEngineGraphClient);
    }

    @Test
    @DisplayName("파기(강제): 연동 권한 폐기가 실패해도 그래프 삭제는 반드시 진행한다"
            + " — releaseExternalResources와의 핵심 차이")
    void forcePurgeExternalResourcesDeletesGraphEvenWhenRevokeFails() {
        ProjectService service = service();
        Project project = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(false);

        service.forcePurgeExternalResources(OWNER_ID);

        verify(aiEngineGraphClient).deleteProjectGraph(FIRST_OWNED_PROJECT_ID);
    }

    @Test
    @DisplayName("파기(강제): 한 프로젝트의 폐기가 실패해도 나머지 프로젝트 순회를 멈추지 않고 그래프를 마저 지운다")
    void forcePurgeExternalResourcesContinuesToNextProjectWhenOneRevokeFails() {
        ProjectService service = service();
        Project first = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        Project second = project(SECOND_OWNED_PROJECT_ID, OWNER_ID, "Second", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(first, second));
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(false);
        when(integrationRevocationService.revokeAll(SECOND_OWNED_PROJECT_ID)).thenReturn(true);

        service.forcePurgeExternalResources(OWNER_ID);

        InOrder inOrder = inOrder(integrationRevocationService, aiEngineGraphClient);
        inOrder.verify(integrationRevocationService).revokeAll(FIRST_OWNED_PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).deleteProjectGraph(FIRST_OWNED_PROJECT_ID);
        inOrder.verify(integrationRevocationService).revokeAll(SECOND_OWNED_PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).deleteProjectGraph(SECOND_OWNED_PROJECT_ID);
    }

    @Test
    @DisplayName("파기(강제): 그래프 삭제 자체의 실패는 여전히 전파돼 호출부가 재시도할 수 있다")
    void forcePurgeExternalResourcesPropagatesGraphDeleteFailure() {
        ProjectService service = service();
        Project project = project(FIRST_OWNED_PROJECT_ID, OWNER_ID, "First", null);
        when(projectRepository.findAllByOwner_IdOrderBySortOrderAsc(OWNER_ID))
                .thenReturn(List.of(project));
        when(integrationRevocationService.revokeAll(FIRST_OWNED_PROJECT_ID)).thenReturn(true);
        doThrow(new BadGatewayException("Failed to delete project graph."))
                .when(aiEngineGraphClient).deleteProjectGraph(FIRST_OWNED_PROJECT_ID);

        assertThatThrownBy(() -> service.forcePurgeExternalResources(OWNER_ID))
                .isInstanceOf(BadGatewayException.class);
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

    private ProjectService service() {
        return new ProjectService(projectRepository, userService, aiEngineGraphClient, integrationRevocationService);
    }
}


