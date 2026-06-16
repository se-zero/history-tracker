package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.webhook.GitHubWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
public class ProjectIntegrationService {

    private static final String PROVIDER_GITHUB = "github";
    private static final String PROVIDER_JIRA = "jira";
    private static final String PROVIDER_SLACK = "slack";

    private static final String GITHUB_REPOSITORY_FULL_NAME = "repository_full_name";
    private static final String GITHUB_BRANCH = "branch";
    private static final String JIRA_PROJECT_KEY = "project_key";
    private static final String JIRA_BASE_URL = "base_url";
    private static final Duration GITHUB_TOKEN_REFRESH_SKEW = Duration.ofMinutes(5);

    private final ProjectIntegrationRepository repository;
    private final CredentialCryptoService credentialCryptoService;
    private final Clock clock;

    @Autowired
    public ProjectIntegrationService(
            ProjectIntegrationRepository repository,
            CredentialCryptoService credentialCryptoService
    ) {
        this(repository, credentialCryptoService, Clock.systemUTC());
    }

    ProjectIntegrationService(
            ProjectIntegrationRepository repository,
            CredentialCryptoService credentialCryptoService,
            Clock clock
    ) {
        this.repository = repository;
        this.credentialCryptoService = credentialCryptoService;
        this.clock = clock;
    }

    public GitHubWebhookIntegrationResolution resolveGitHubPullRequestWebhook(GitHubWebhookPayload payload) {
        Optional<ProjectIntegrationRepository.IntegrationRow> githubMatch = repository.findGitHubWebhookIntegration(
                        payload.installationId(),
                        payload.repositoryId(),
                        payload.repositoryFullName()
                );
        if (githubMatch.isEmpty()) {
            return GitHubWebhookIntegrationResolution.notFound();
        }
        if (requiresGitHubTokenRefresh(githubMatch.orElseThrow())) {
            return GitHubWebhookIntegrationResolution.tokenRefreshRequired();
        }
        return buildContext(githubMatch.orElseThrow())
                .map(GitHubWebhookIntegrationResolution::ready)
                .orElseGet(GitHubWebhookIntegrationResolution::notFound);
    }

    public Optional<GitHubIntegration> resolveGitHub(UUID projectId) {
        return findIntegration(projectId, PROVIDER_GITHUB)
                .flatMap(this::buildGitHubIntegrationSafely);
    }

    public Optional<JiraIntegration> resolveJira(UUID projectId) {
        return findIntegration(projectId, PROVIDER_JIRA)
                .flatMap(integration -> buildOptionalIntegration(
                        () -> buildJiraIntegration(integration),
                        PROVIDER_JIRA,
                        projectId.toString()
                ));
    }

    public Optional<SlackIntegration> resolveSlack(UUID projectId) {
        return findIntegration(projectId, PROVIDER_SLACK)
                .flatMap(integration -> buildOptionalIntegration(
                        () -> buildSlackIntegration(integration),
                        PROVIDER_SLACK,
                        projectId.toString()
                ));
    }

    private Optional<ProjectCollectionContext> buildContext(ProjectIntegrationRepository.IntegrationRow githubMatch) {
        List<ProjectIntegrationRepository.IntegrationRow> integrations = repository.findAllByProjectId(githubMatch.projectId());
        Optional<GitHubIntegration> github = Optional.empty();
        Optional<JiraIntegration> jira = Optional.empty();
        Optional<SlackIntegration> slack = Optional.empty();

        for (ProjectIntegrationRepository.IntegrationRow integration : integrations) {
            switch (integration.provider()) {
                case PROVIDER_GITHUB -> github = buildGitHubIntegration(integration);
                case PROVIDER_JIRA -> jira = buildOptionalIntegration(
                        () -> buildJiraIntegration(integration),
                        PROVIDER_JIRA,
                        integration.projectId().toString()
                );
                case PROVIDER_SLACK -> slack = buildOptionalIntegration(
                        () -> buildSlackIntegration(integration),
                        PROVIDER_SLACK,
                        integration.projectId().toString()
                );
                default -> log.warn("Unsupported integration provider: projectId={}, provider={}",
                        integration.projectId(), integration.provider());
            }
        }

        if (github.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectCollectionContext(
                githubMatch.projectId().toString(),
                github.orElseThrow(),
                jira,
                slack
        ));
    }

    private Optional<ProjectIntegrationRepository.IntegrationRow> findIntegration(UUID projectId, String provider) {
        return repository.findAllByProjectId(projectId).stream()
                .filter(integration -> provider.equals(integration.provider()))
                .findFirst();
    }

    private Optional<GitHubIntegration> buildGitHubIntegrationSafely(
            ProjectIntegrationRepository.IntegrationRow integration
    ) {
        try {
            return buildGitHubIntegration(integration);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("Skipping invalid integration: projectId={}, provider={}",
                    integration.projectId(), PROVIDER_GITHUB, exception);
            return Optional.empty();
        }
    }

    private Optional<GitHubIntegration> buildGitHubIntegration(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedInstallationToken() == null || integration.installationTokenExpiresAt() == null) {
            log.warn("GitHub installation token is not cached: projectId={}", integration.projectId());
            return Optional.empty();
        }
        if (!integration.installationTokenExpiresAt().isAfter(Instant.now(clock))) {
            log.warn("GitHub installation token is expired: projectId={}, expiresAt={}",
                    integration.projectId(), integration.installationTokenExpiresAt());
            return Optional.empty();
        }

        String token = credentialCryptoService.decrypt(integration.encryptedInstallationToken());
        return Optional.of(new GitHubIntegration(
                bearer(token),
                requiredString(integration.externalRef(), GITHUB_REPOSITORY_FULL_NAME),
                optionalString(integration.externalRef(), GITHUB_BRANCH)
        ));
    }

    private boolean requiresGitHubTokenRefresh(ProjectIntegrationRepository.IntegrationRow integration) {
        return integration.encryptedInstallationToken() == null
                || integration.installationTokenExpiresAt() == null
                || !integration.installationTokenExpiresAt()
                .isAfter(Instant.now(clock).plus(GITHUB_TOKEN_REFRESH_SKEW));
    }

    private JiraIntegration buildJiraIntegration(ProjectIntegrationRepository.IntegrationRow integration) {
        return new JiraIntegration(
                credentialCryptoService.decrypt(requiredEncryptedCredential(integration)),
                requiredString(integration.externalRef(), JIRA_PROJECT_KEY),
                requiredString(integration.externalRef(), JIRA_BASE_URL)
        );
    }

    private SlackIntegration buildSlackIntegration(ProjectIntegrationRepository.IntegrationRow integration) {
        return new SlackIntegration(bearer(credentialCryptoService.decrypt(requiredEncryptedCredential(integration))));
    }

    private <T> Optional<T> buildOptionalIntegration(Supplier<T> supplier, String provider, String projectId) {
        try {
            return Optional.of(supplier.get());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("Skipping invalid optional integration: projectId={}, provider={}",
                    projectId, provider, exception);
            return Optional.empty();
        }
    }

    private byte[] requiredEncryptedCredential(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        return integration.encryptedCredential();
    }

    private String requiredString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing external_ref value: " + key);
    }

    // branch는 과거 연동에는 없을 수 있어 선택값으로 읽는다 (null이면 기본 브랜치 수집)
    private String optionalString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String bearer(String token) {
        if (token.startsWith("Bearer ")) {
            return token;
        }
        return "Bearer " + token;
    }
}
