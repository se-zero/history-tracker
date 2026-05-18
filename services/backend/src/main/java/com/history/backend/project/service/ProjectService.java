package com.history.backend.project.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
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

    @Transactional
    public Project createProject(UUID ownerId, String name, String description) {
        User owner = userService.getActiveUser(ownerId);
        validateNameAvailable(ownerId, name);
        try {
            // flush를 강제해 UPDATE의 unique 제약 위반을 트랜잭션 내에서 감지
            return projectRepository.saveAndFlush(new Project(owner, name, description));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Project name already exists.");
        }
    }

    @Transactional(readOnly = true)
    public List<Project> findProjects(UUID ownerId) {
        userService.getActiveUser(ownerId);
        return projectRepository.findAllByOwner_IdAndDeletedAtIsNullOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public Project getProject(UUID ownerId, UUID projectId) {
        userService.getActiveUser(ownerId);
        Project project = findActiveProject(projectId);
        validateOwner(project, ownerId);
        return project;
    }

    @Transactional
    public Project updateProject(UUID ownerId, UUID projectId, String name, String description) {
        userService.getActiveUser(ownerId);
        Project project = findActiveProject(projectId);
        validateOwner(project, ownerId);
        validateNameAvailableForUpdate(ownerId, projectId, name);
        project.updateDetails(name, description);
        try {
            return projectRepository.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Project name already exists.");
        }
    }

    @Transactional
    public void deleteProject(UUID ownerId, UUID projectId) {
        userService.getActiveUser(ownerId);
        Project project = findActiveProject(projectId);
        validateOwner(project, ownerId);
        project.softDelete(Instant.now());
    }

    private Project findActiveProject(UUID projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found."));
    }

    private void validateOwner(Project project, UUID ownerId) {
        if (!project.isOwnedBy(ownerId)) {
            throw new ForbiddenException("Project access denied.");
        }
    }

    private void validateNameAvailable(UUID ownerId, String name) {
        if (projectRepository.existsActiveByOwnerIdAndNameIgnoreCase(ownerId, name)) {
            throw new ConflictException("Project name already exists.");
        }
    }

    private void validateNameAvailableForUpdate(UUID ownerId, UUID projectId, String name) {
        if (projectRepository.existsActiveByOwnerIdAndNameIgnoreCaseExcludingId(ownerId, name, projectId)) {
            throw new ConflictException("Project name already exists.");
        }
    }
}
