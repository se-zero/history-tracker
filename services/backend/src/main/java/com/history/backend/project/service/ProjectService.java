package com.history.backend.project.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.graph.service.AiEngineGraphClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserService userService;
    private final AiEngineGraphClient aiEngineGraphClient;

    @Transactional
    public Project createProject(UUID ownerId, String name, String description) {
        User owner = userService.getActiveUser(ownerId);
        String normalizedName = name.trim();
        validateNameAvailable(ownerId, normalizedName);
        try {
            // flush를 강제해 unique 제약 위반을 트랜잭션 내에서 감지
            return projectRepository.saveAndFlush(new Project(owner, normalizedName, description));
        } catch (DataIntegrityViolationException exception) {
            // 동시 생성 경합 시 unique 제약 위반을 409로 변환
            throw new ConflictException("Project name already exists.");
        }
    }

    @Transactional(readOnly = true)
    public List<Project> findProjects(UUID ownerId) {
        userService.getActiveUser(ownerId);
        return projectRepository.findAllByOwner_IdOrderByCreatedAtDesc(ownerId);
    }

    // 소유 검증 포함 프로젝트 조회 — 타 모듈의 공통 접근 검증 진입점
    @Transactional(readOnly = true)
    public Project getProject(UUID ownerId, UUID projectId) {
        userService.getActiveUser(ownerId);
        Project project = findProject(projectId);
        validateOwner(project, ownerId);
        return project;
    }

    @Transactional
    public Project updateProject(UUID ownerId, UUID projectId, String name, String description) {
        userService.getActiveUser(ownerId);
        Project project = findProject(projectId);
        validateOwner(project, ownerId);
        String normalizedName = name.trim();
        validateNameAvailableForUpdate(ownerId, projectId, normalizedName);
        project.updateDetails(normalizedName, description);
        try {
            return projectRepository.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            // 동시 수정 경합 시 unique 제약 위반을 409로 변환
            throw new ConflictException("Project name already exists.");
        }
    }

    // 외부 HTTP 호출(그래프 삭제)을 트랜잭션 밖에 둔다 — @Transactional이면 검증 read가 잡은 JDBC
    // 커넥션을 호출 내내 점유해(대형 그래프 삭제는 수 초) HikariCP pool이 고갈된다. 그래프를 먼저
    // 지우는 건 멱등이라서다: RDB delete가 뒤에서 실패해도 재시도 시 그래프 삭제가 no-op(0)으로
    // 통과하고 RDB만 마저 지워 복구된다 (원자적 보장은 아님 — 재시도로 수렴).
    public void deleteProject(UUID ownerId, UUID projectId) {
        userService.getActiveUser(ownerId);
        // owner를 함께 로딩 — 트랜잭션 밖이라 lazy owner가 detached되므로 소유권 검증 전에 fetch join
        Project project = projectRepository.findByIdWithOwner(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found."));
        validateOwner(project, ownerId);
        aiEngineGraphClient.deleteProjectGraph(projectId);
        projectRepository.deleteById(projectId);
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found."));
    }

    private void validateOwner(Project project, UUID ownerId) {
        if (!project.isOwnedBy(ownerId)) {
            throw new ForbiddenException("Project access denied.");
        }
    }

    private void validateNameAvailable(UUID ownerId, String name) {
        if (projectRepository.existsByOwnerIdAndNameIgnoreCase(ownerId, name)) {
            throw new ConflictException("Project name already exists.");
        }
    }

    private void validateNameAvailableForUpdate(UUID ownerId, UUID projectId, String name) {
        if (projectRepository.existsByOwnerIdAndNameIgnoreCaseExcludingId(ownerId, name, projectId)) {
            throw new ConflictException("Project name already exists.");
        }
    }
}

