package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.common.error.ConflictException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;
    private final ProjectService projectService;
    private final GitHubInstallationService gitHubInstallationService;

    @Transactional
    public Integration connectGitHubRepository(
            UUID ownerId,
            UUID projectId,
            UUID installationId,
            Long repositoryId,
            String repositoryFullName
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        GitHubInstallation installation = gitHubInstallationService.getInstallationForInstaller(ownerId, installationId);
        validateProviderAvailable(projectId, IntegrationProvider.GITHUB);

        String normalizedRepositoryFullName = repositoryFullName.trim();
        try {
            return integrationRepository.saveAndFlush(Integration.github(
                    project,
                    installation,
                    repositoryId,
                    normalizedRepositoryFullName
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("GitHub integration already exists.");
        }
    }

    private void validateProviderAvailable(UUID projectId, IntegrationProvider provider) {
        if (integrationRepository.existsByProject_IdAndProvider(projectId, provider)) {
            throw new ConflictException("GitHub integration already exists.");
        }
    }
}
