package com.history.backend.integration.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.slack.service.SlackClient;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;
    private final ProjectService projectService;
    private final GitHubInstallationService gitHubInstallationService;
    private final CredentialCryptoService credentialCryptoService;
    private final SlackClient slackClient;
    private final JiraClient jiraClient;
    private final PlatformTransactionManager transactionManager;

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

    public Integration connectSlackWorkspace(
            UUID ownerId,
            UUID projectId,
            String token
    ) {
        String normalizedToken = token.trim();
        SlackClient.SlackWorkspace workspace = slackClient.verifyToken(normalizedToken);
        byte[] encryptedCredential = credentialCryptoService.encrypt(normalizedToken);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> saveSlackWorkspace(
                ownerId,
                projectId,
                workspace,
                encryptedCredential
        ));
    }

    public Integration connectJiraProject(
            UUID ownerId,
            UUID projectId,
            String baseUrl,
            String projectKey,
            String email,
            String apiToken
    ) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String normalizedProjectKey = projectKey.trim();
        String normalizedEmail = email.trim();
        String normalizedApiToken = apiToken.trim();

        JiraClient.JiraProject jiraProject = jiraClient.verifyProject(
                normalizedBaseUrl,
                normalizedProjectKey,
                normalizedEmail,
                normalizedApiToken
        );
        byte[] encryptedCredential = credentialCryptoService.encrypt(
                normalizedEmail + ":" + normalizedApiToken
        );

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> saveJiraProject(
                ownerId,
                projectId,
                normalizedBaseUrl,
                jiraProject,
                encryptedCredential
        ));
    }

    private Integration saveSlackWorkspace(
            UUID ownerId,
            UUID projectId,
            SlackClient.SlackWorkspace workspace,
            byte[] encryptedCredential
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        validateProviderAvailable(projectId, IntegrationProvider.SLACK);

        try {
            return integrationRepository.saveAndFlush(Integration.slack(
                    project,
                    workspace.id(),
                    workspace.name(),
                    encryptedCredential
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Slack integration already exists.");
        }
    }

    private Integration saveJiraProject(
            UUID ownerId,
            UUID projectId,
            String baseUrl,
            JiraClient.JiraProject jiraProject,
            byte[] encryptedCredential
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        validateProviderAvailable(projectId, IntegrationProvider.JIRA);

        try {
            return integrationRepository.saveAndFlush(Integration.jira(
                    project,
                    jiraProject.key(),
                    jiraProject.name(),
                    baseUrl,
                    encryptedCredential
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Jira integration already exists.");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BadRequestException("Jira base URL must start with https://.");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new BadRequestException("Jira base URL host is required.");
            }
            validatePublicHost(host);
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw new BadRequestException("Jira base URL is invalid.");
        }
    }

    private void validatePublicHost(String host) {
        String normalizedHost = host.toLowerCase();
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost")) {
            throw new BadRequestException("Jira base URL host must be public.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!isPublicAddress(address)) {
                    throw new BadRequestException("Jira base URL host must be public.");
                }
            }
        } catch (UnknownHostException exception) {
            throw new BadRequestException("Jira base URL host is invalid.");
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !isUniqueLocalIpv6Address(address);
    }

    private boolean isUniqueLocalIpv6Address(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte firstByte = address.getAddress()[0];
        return (firstByte & 0xfe) == 0xfc;
    }

    private void validateProviderAvailable(UUID projectId, IntegrationProvider provider) {
        if (integrationRepository.existsByProject_IdAndProvider(projectId, provider)) {
            throw new ConflictException(providerDisplayName(provider) + " integration already exists.");
        }
    }

    private String providerDisplayName(IntegrationProvider provider) {
        return switch (provider) {
            case GITHUB -> "GitHub";
            case SLACK -> "Slack";
            case JIRA -> "Jira";
        };
    }
}
