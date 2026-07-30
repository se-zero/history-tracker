package com.history.pipeline_worker.collection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.webhook.GitHubWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String GITHUB_REPOSITORY_FULL_NAME = "repository_full_name";
    private static final String GITHUB_BRANCH = "branch";
    private static final String JIRA_PROJECT_KEY = "project_key";
    private static final String JIRA_CLOUD_ID = "cloud_id";
    private static final String JIRA_ACCESS_TOKEN = "access_token";
    private static final Duration GITHUB_TOKEN_REFRESH_SKEW = Duration.ofMinutes(5);
    private static final TypeReference<Map<String, Object>> CREDENTIAL_JSON_TYPE = new TypeReference<>() {
    };

    private final ProjectIntegrationRepository repository;
    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper;
    private final String jiraGatewayBaseUrl;
    private final Clock clock;

    @Autowired
    public ProjectIntegrationService(
            ProjectIntegrationRepository repository,
            CredentialCryptoService credentialCryptoService,
            ObjectMapper objectMapper,
            @Value("${app.jira.gateway-base-url}") String jiraGatewayBaseUrl
    ) {
        this(repository, credentialCryptoService, objectMapper, jiraGatewayBaseUrl, Clock.systemUTC());
    }

    ProjectIntegrationService(
            ProjectIntegrationRepository repository,
            CredentialCryptoService credentialCryptoService,
            ObjectMapper objectMapper,
            String jiraGatewayBaseUrl,
            Clock clock
    ) {
        this.repository = repository;
        this.credentialCryptoService = credentialCryptoService;
        this.objectMapper = objectMapper;
        this.jiraGatewayBaseUrl = jiraGatewayBaseUrl;
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
        return findIntegration(projectId, CollectionProvider.GITHUB)
                .flatMap(this::buildGitHubIntegrationSafely);
    }

    public Optional<JiraIntegration> resolveJira(UUID projectId) {
        return findIntegration(projectId, CollectionProvider.JIRA)
                .flatMap(integration -> buildOptionalIntegration(
                        () -> buildJiraIntegration(integration),
                        CollectionProvider.JIRA.value(),
                        projectId.toString()
                ));
    }

    public Optional<SlackIntegration> resolveSlack(UUID projectId) {
        return findIntegration(projectId, CollectionProvider.SLACK)
                .flatMap(integration -> buildOptionalIntegration(
                        () -> buildSlackIntegration(integration),
                        CollectionProvider.SLACK.value(),
                        projectId.toString()
                ));
    }

    private Optional<ProjectCollectionContext> buildContext(ProjectIntegrationRepository.IntegrationRow githubMatch) {
        List<ProjectIntegrationRepository.IntegrationRow> integrations = repository.findAllByProjectId(githubMatch.projectId());
        Optional<GitHubIntegration> github = Optional.empty();
        Optional<JiraIntegration> jira = Optional.empty();
        Optional<SlackIntegration> slack = Optional.empty();

        for (ProjectIntegrationRepository.IntegrationRow integration : integrations) {
            CollectionProvider provider = CollectionProvider.find(integration.provider()).orElse(null);
            if (provider == null) {
                log.warn("Unsupported integration provider: projectId={}, provider={}",
                        integration.projectId(), integration.provider());
                continue;
            }
            switch (provider) {
                case GITHUB -> github = buildGitHubIntegration(integration);
                case JIRA -> jira = buildOptionalIntegration(
                        () -> buildJiraIntegration(integration),
                        provider.value(),
                        integration.projectId().toString()
                );
                case SLACK -> slack = buildOptionalIntegration(
                        () -> buildSlackIntegration(integration),
                        provider.value(),
                        integration.projectId().toString()
                );
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

    private Optional<ProjectIntegrationRepository.IntegrationRow> findIntegration(UUID projectId, CollectionProvider provider) {
        return repository.findAllByProjectId(projectId).stream()
                .filter(integration -> provider.value().equals(integration.provider()))
                .findFirst();
    }

    private Optional<GitHubIntegration> buildGitHubIntegrationSafely(
            ProjectIntegrationRepository.IntegrationRow integration
    ) {
        try {
            return buildGitHubIntegration(integration);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("Skipping invalid integration: projectId={}, provider={}",
                    integration.projectId(), CollectionProvider.GITHUB.value(), exception);
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
        Map<String, Object> credential = parseJiraCredential(
                credentialCryptoService.decrypt(requiredEncryptedCredential(integration))
        );
        return new JiraIntegration(
                bearer(requiredCredentialString(credential, JIRA_ACCESS_TOKEN)),
                requiredString(integration.externalRef(), JIRA_PROJECT_KEY),
                jiraGatewayBaseUrl + "/" + requiredString(integration.externalRef(), JIRA_CLOUD_ID)
        );
    }

    // OAuth 전환 후 Jira credential은 JSON({access_token, refresh_token, expires_at})이다.
    // Jackson 예외를 그대로 던지면 buildOptionalIntegration의 안전망(IllegalArgumentException|IllegalStateException만
    // 잡음)을 우회해 잘못된 연동이 webhook 수집 전체를 실패시키므로 반드시 IllegalStateException으로 감싼다.
    private Map<String, Object> parseJiraCredential(String json) {
        try {
            return objectMapper.readValue(json, CREDENTIAL_JSON_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Jira credential JSON.", exception);
        }
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

    // access_token은 external_ref가 아니라 암호화된 credential JSON 안에 있어 requiredString과 메시지를
    // 분리한다 — 재사용하면 "Missing external_ref value: access_token"처럼 엉뚱한 위치를 가리켜 오도한다.
    // 패키지 접근으로 열어 테스트에서 메시지를 직접 검증한다.
    String requiredCredentialString(Map<String, Object> credential, String key) {
        Object value = credential.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing Jira credential field: " + key);
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
