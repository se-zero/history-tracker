package com.history.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.mcp.domain.McpWorkspaceBinding;
import com.history.backend.mcp.domain.McpWorkspaceBindingId;
import com.history.backend.mcp.repository.McpWorkspaceBindingRepository;
import com.history.backend.mcp.service.McpBindingService.Bound;
import com.history.backend.mcp.service.McpBindingService.NoProjects;
import com.history.backend.mcp.service.McpBindingService.Resolution;
import com.history.backend.mcp.service.McpBindingService.Unbound;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpBindingService: (계정, 작업 폴더) → 프로젝트 연결 저장·해석")
class McpBindingServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID OTHER_USER_ID = UUID.fromString("0f80f8ae-3fb1-4d90-978e-579a890e9478");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("266acdfb-5dfd-4d26-8808-a92eb4f983ee");
    private static final String PATH = "/home/me/repo";
    private static final String WINDOWS_PATH = "C:\\git\\repo";

    @Mock
    private McpWorkspaceBindingRepository bindingRepository;

    @Mock
    private ProjectService projectService;

    @Test
    @DisplayName("새 경로면 연결을 저장하고 프로젝트를 반환")
    void bindSavesNewBindingAndReturnsProject() {
        McpBindingService service = service();
        Project project = project(PROJECT_ID, "History Tracker");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project);
        when(bindingRepository.findById(id(USER_ID, PATH))).thenReturn(Optional.empty());

        Project result = service.bind(USER_ID, PATH, PROJECT_ID);

        assertThat(result).isSameAs(project);
        ArgumentCaptor<McpWorkspaceBinding> captor = ArgumentCaptor.forClass(McpWorkspaceBinding.class);
        verify(bindingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(id(USER_ID, PATH));
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
    }

    @Test
    @DisplayName("이미 연결된 경로면 프로젝트만 교체 — 새 행을 만들지 않는다")
    void bindChangesProjectOfExistingBindingWithoutCreatingNewRow() {
        McpBindingService service = service();
        McpWorkspaceBinding existing = new McpWorkspaceBinding(USER_ID, PATH, OTHER_PROJECT_ID);
        Project project = project(PROJECT_ID, "History Tracker");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project);
        when(bindingRepository.findById(id(USER_ID, PATH))).thenReturn(Optional.of(existing));

        Project result = service.bind(USER_ID, PATH, PROJECT_ID);

        assertThat(result).isSameAs(project);
        assertThat(existing.getProjectId()).isEqualTo(PROJECT_ID);
        // 저장소를 다시 부르더라도 기존 행이어야 한다 — 같은 키의 새 인스턴스는 안 된다
        verify(bindingRepository, never()).save(argThat(binding -> binding != existing));
    }

    @Test
    @DisplayName("공백·끝 구분자를 붙여 호출해도 정규화된 같은 키로 저장")
    void bindStoresUnderNormalizedPath() {
        McpBindingService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project(PROJECT_ID, "History Tracker"));
        when(bindingRepository.findById(id(USER_ID, WINDOWS_PATH))).thenReturn(Optional.empty());

        service.bind(USER_ID, "  " + WINDOWS_PATH + "\\ ", PROJECT_ID);

        ArgumentCaptor<McpWorkspaceBinding> captor = ArgumentCaptor.forClass(McpWorkspaceBinding.class);
        verify(bindingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(id(USER_ID, WINDOWS_PATH));
    }

    @Test
    @DisplayName("남의 프로젝트면 ForbiddenException 전파 — 저장하지 않는다")
    void bindPropagatesForbiddenAndDoesNotSave() {
        McpBindingService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID))
                .thenThrow(new ForbiddenException("Project access denied."));

        assertThatThrownBy(() -> service.bind(USER_ID, PATH, PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(bindingRepository, never()).save(any(McpWorkspaceBinding.class));
    }

    @Test
    @DisplayName("없는 프로젝트면 NotFoundException 전파 — 저장하지 않는다")
    void bindPropagatesNotFoundAndDoesNotSave() {
        McpBindingService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID))
                .thenThrow(new NotFoundException("Project not found."));

        assertThatThrownBy(() -> service.bind(USER_ID, PATH, PROJECT_ID))
                .isInstanceOf(NotFoundException.class);

        verify(bindingRepository, never()).save(any(McpWorkspaceBinding.class));
    }

    @Test
    @DisplayName("잘못된 경로면 IllegalArgumentException — ProjectService도 저장소도 부르지 않는다")
    void bindRejectsInvalidPathWithoutTouchingCollaborators() {
        McpBindingService service = service();

        assertThatThrownBy(() -> service.bind(USER_ID, "./repo", PROJECT_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(projectService, bindingRepository);
    }

    @Test
    @DisplayName("저장된 연결이 있으면 Bound — 저장된 projectId로 프로젝트를 다시 확인")
    void resolveReturnsBoundWhenBindingExists() {
        McpBindingService service = service();
        Project project = project(PROJECT_ID, "History Tracker");
        when(bindingRepository.findById(id(USER_ID, PATH)))
                .thenReturn(Optional.of(new McpWorkspaceBinding(USER_ID, PATH, PROJECT_ID)));
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project);

        Resolution result = service.resolve(USER_ID, PATH);

        assertThat(result).isEqualTo(new Bound(project));
        verify(bindingRepository, never()).save(any(McpWorkspaceBinding.class));
    }

    @Test
    @DisplayName("끝 구분자를 붙여 조회해도 정규화된 키로 찾는다")
    void resolveLooksUpNormalizedPath() {
        McpBindingService service = service();
        Project project = project(PROJECT_ID, "History Tracker");
        when(bindingRepository.findById(id(USER_ID, PATH)))
                .thenReturn(Optional.of(new McpWorkspaceBinding(USER_ID, PATH, PROJECT_ID)));
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project);

        assertThat(service.resolve(USER_ID, PATH + "/")).isEqualTo(new Bound(project));
    }

    @Test
    @DisplayName("연결된 프로젝트가 사라졌거나 접근 불가면 그 예외를 전파 — 조용히 Unbound로 바꾸지 않는다")
    void resolvePropagatesWhenBoundProjectIsNoLongerAccessible() {
        McpBindingService service = service();
        when(bindingRepository.findById(id(USER_ID, PATH)))
                .thenReturn(Optional.of(new McpWorkspaceBinding(USER_ID, PATH, PROJECT_ID)));
        when(projectService.getProject(USER_ID, PROJECT_ID))
                .thenThrow(new NotFoundException("Project not found."));

        assertThatThrownBy(() -> service.resolve(USER_ID, PATH)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("연결도 프로젝트도 없으면 NoProjects")
    void resolveReturnsNoProjectsWhenUserHasNoProjects() {
        McpBindingService service = service();
        when(bindingRepository.findById(id(USER_ID, PATH))).thenReturn(Optional.empty());
        when(projectService.findProjects(USER_ID)).thenReturn(List.of());

        Resolution result = service.resolve(USER_ID, PATH);

        assertThat(result).isEqualTo(new NoProjects());
        verify(bindingRepository, never()).save(any(McpWorkspaceBinding.class));
    }

    @Test
    @DisplayName("연결이 없고 프로젝트가 1건이어도 자동 연결하지 않는다 — Unbound(1건), 저장 없음")
    void resolveDoesNotAutoBindSingleProject() {
        McpBindingService service = service();
        Project only = project(PROJECT_ID, "History Tracker");
        when(bindingRepository.findById(id(USER_ID, PATH))).thenReturn(Optional.empty());
        when(projectService.findProjects(USER_ID)).thenReturn(List.of(only));

        Resolution result = service.resolve(USER_ID, PATH);

        assertThat(result).isInstanceOf(Unbound.class);
        assertThat(((Unbound) result).projects()).containsExactly(only);
        verify(bindingRepository, never()).save(any(McpWorkspaceBinding.class));
        verify(bindingRepository, never()).saveAndFlush(any(McpWorkspaceBinding.class));
        verify(bindingRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("연결이 없고 프로젝트가 여러 건이면 Unbound — 목록 순서 유지")
    void resolveReturnsUnboundWithProjectsInOrder() {
        McpBindingService service = service();
        Project first = project(PROJECT_ID, "Alpha");
        Project second = project(OTHER_PROJECT_ID, "Beta");
        when(bindingRepository.findById(id(USER_ID, PATH))).thenReturn(Optional.empty());
        when(projectService.findProjects(USER_ID)).thenReturn(List.of(first, second));

        Resolution result = service.resolve(USER_ID, PATH);

        assertThat(result).isInstanceOf(Unbound.class);
        assertThat(((Unbound) result).projects()).containsExactly(first, second);
        verify(bindingRepository, never()).save(any(McpWorkspaceBinding.class));
    }

    @Test
    @DisplayName("다른 사용자의 같은 경로 연결은 보지 않는다 — 조회 키에 userId 포함")
    void resolveIgnoresOtherUsersBindingForSamePath() {
        McpBindingService service = service();
        Project mine = project(PROJECT_ID, "History Tracker");
        // 다른 사용자 키로 조회될 때만 행을 돌려준다
        when(bindingRepository.findById(any(McpWorkspaceBindingId.class))).thenAnswer(invocation ->
                id(OTHER_USER_ID, PATH).equals(invocation.getArgument(0))
                        ? Optional.of(new McpWorkspaceBinding(OTHER_USER_ID, PATH, OTHER_PROJECT_ID))
                        : Optional.empty());
        when(projectService.findProjects(USER_ID)).thenReturn(List.of(mine));

        Resolution result = service.resolve(USER_ID, PATH);

        assertThat(result).isEqualTo(new Unbound(List.of(mine)));
        verify(bindingRepository).findById(id(USER_ID, PATH));
    }

    @Test
    @DisplayName("잘못된 경로면 IllegalArgumentException — ProjectService도 저장소도 부르지 않는다")
    void resolveRejectsInvalidPathWithoutTouchingCollaborators() {
        McpBindingService service = service();

        assertThatThrownBy(() -> service.resolve(USER_ID, "repo"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(projectService, bindingRepository);
    }

    private McpWorkspaceBindingId id(UUID userId, String path) {
        return new McpWorkspaceBindingId(userId, path);
    }

    private Project project(UUID id, String name) {
        User owner = new User("github", USER_ID.toString(), "user@example.com", "User", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        Project project = new Project(owner, name, null);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }

    private McpBindingService service() {
        return new McpBindingService(bindingRepository, projectService);
    }
}
