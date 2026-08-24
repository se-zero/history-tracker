package com.history.backend.project.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.graph.service.AiEngineGraphClient;
import com.history.backend.integration.service.IntegrationRevocationService;
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
    private final IntegrationRevocationService integrationRevocationService;

    @Transactional
    public Project createProject(UUID ownerId, String name, String description) {
        User owner = userService.getActiveUser(ownerId);
        String normalizedName = name.trim();
        validateNameAvailable(ownerId, normalizedName);
        Project project = new Project(owner, normalizedName, description);
        // 새 프로젝트는 소유자 목록 맨 끝에 배치한다.
        project.updateSortOrder(projectRepository.findMaxSortOrderByOwnerId(ownerId) + 1);
        try {
            // flush를 강제해 unique 제약 위반을 트랜잭션 내에서 감지
            return projectRepository.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            // 동시 생성 경합 시 unique 제약 위반을 409로 변환
            throw new ConflictException("Project name already exists.");
        }
    }

    @Transactional(readOnly = true)
    public List<Project> findProjects(UUID ownerId) {
        userService.getActiveUser(ownerId);
        return projectRepository.findAllByOwner_IdOrderBySortOrderAsc(ownerId);
    }

    // 드래그로 바뀐 순서를 저장한다. orderedIds 순서대로 0부터 재채번한다.
    @Transactional
    public List<Project> reorderProjects(UUID ownerId, List<UUID> orderedIds) {
        userService.getActiveUser(ownerId);
        List<Project> projects = projectRepository.findAllByOwner_IdOrderBySortOrderAsc(ownerId);
        Map<UUID, Project> byId = new HashMap<>();
        for (Project project : projects) {
            byId.put(project.getId(), project);
        }

        int position = 0;
        for (UUID id : orderedIds) {
            Project project = byId.remove(id);
            // 타 사용자·미존재 id는 거부. 중복 id는 이미 제거돼 무시된다.
            if (project == null) {
                if (projects.stream().noneMatch(p -> p.getId().equals(id))) {
                    throw new NotFoundException("Project not found: " + id);
                }
                continue;
            }
            project.updateSortOrder(position++);
        }
        // 요청에 빠진 프로젝트(동시 생성 등)는 기존 순서를 유지하며 뒤에 붙인다.
        for (Project project : projects) {
            if (byId.containsKey(project.getId())) {
                project.updateSortOrder(position++);
            }
        }

        projectRepository.saveAll(projects);
        projects.sort(Comparator.comparingInt(Project::getSortOrder));
        return projects;
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
    // 연동 권한 폐기를 가장 먼저 하는 이유: RDB 행이 지워지면 폐기에 쓸 자격증명(암호화된 토큰)이
    // 사라진다(IntegrationService.disconnect가 같은 이유로 폐기를 첫 단계에 둔다).
    public void deleteProject(UUID ownerId, UUID projectId) {
        userService.getActiveUser(ownerId);
        // owner를 함께 로딩 — 트랜잭션 밖이라 lazy owner가 detached되므로 소유권 검증 전에 fetch join
        Project project = projectRepository.findByIdWithOwner(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found."));
        validateOwner(project, ownerId);
        integrationRevocationService.revokeAll(projectId);
        aiEngineGraphClient.deleteProjectGraph(projectId);
        projectRepository.deleteById(projectId);
    }

    // 파기 배치(UserPurgeService) 전용 진입점 — 파기 대상은 이미 soft-delete된 사용자라
    // getActiveUser 검증을 타면 예외가 던져진다. 인가가 필요한 사용자 경로가 아니라 내부 정리
    // 로직이므로 소유권 검증 없이 owner의 전체 프로젝트를 순회한다.
    // RDB 삭제는 하지 않는다 — 사용자 행 삭제의 FK CASCADE가 프로젝트·연동·대화를 함께 지운다.
    // @Transactional을 붙이지 않는 이유는 deleteProject와 동일: 외부 HTTP 호출(폐기·그래프 삭제)이
    // 트랜잭션 커넥션을 점유하면 안 된다. 그래프 삭제 실패는 전파해 호출부(UserPurgeService)가
    // 그 사용자를 건너뛰고 다음 회차에 재시도할 수 있게 한다.
    public void releaseExternalResources(UUID ownerId) {
        List<Project> projects = projectRepository.findAllByOwner_IdOrderBySortOrderAsc(ownerId);
        for (Project project : projects) {
            integrationRevocationService.revokeAll(project.getId());
            aiEngineGraphClient.deleteProjectGraph(project.getId());
        }
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

