package com.history.backend.mcp.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.mcp.domain.McpWorkspaceBinding;
import com.history.backend.mcp.domain.McpWorkspaceBindingId;
import com.history.backend.mcp.repository.McpWorkspaceBindingRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class McpBindingService {

    private final McpWorkspaceBindingRepository bindingRepository;
    private final ProjectService projectService;

    @Transactional
    public Project bind(UUID userId, String workspace, UUID projectId) {
        String path = WorkspacePathNormalizer.normalize(workspace);
        Project project = projectService.getProject(userId, projectId);
        bindingRepository.findById(new McpWorkspaceBindingId(userId, path)).ifPresentOrElse(
                binding -> binding.changeProject(projectId),
                () -> bindingRepository.save(new McpWorkspaceBinding(userId, path, projectId)));
        return project;
    }

    @Transactional(readOnly = true)
    public Resolution resolve(UUID userId, String workspace) {
        String path = WorkspacePathNormalizer.normalize(workspace);
        McpWorkspaceBinding binding = bindingRepository.findById(new McpWorkspaceBindingId(userId, path))
                .orElse(null);
        if (binding != null) {
            // 저장 뒤 프로젝트가 삭제·이전될 수 있고 getProject가 탈퇴 계정도 막으므로 소유 검증을 매번 다시 한다
            return new Bound(projectService.getProject(userId, binding.getProjectId()));
        }
        // 프로젝트가 하나뿐이어도 자동으로 연결하지 않는다 — 어느 프로젝트에 물을지는 사용자가 정한다
        List<Project> projects = projectService.findProjects(userId);
        if (projects.isEmpty()) {
            return new NoProjects();
        }
        return new Unbound(projects);
    }

    public sealed interface Resolution permits Bound, Unbound, NoProjects {
    }

    public record Bound(Project project) implements Resolution {
    }

    public record Unbound(List<Project> projects) implements Resolution {
    }

    public record NoProjects() implements Resolution {
    }
}
