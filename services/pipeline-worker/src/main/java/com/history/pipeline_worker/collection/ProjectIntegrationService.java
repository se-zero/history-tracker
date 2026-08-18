package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.webhook.GitHubWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DB integration 행을 수집 요청으로 바꾸는 경계.
 *
 * <p>provider별 자격증명·external_ref 해석은 각 {@link SourceCollector}가 소유하고, 여기서는
 * 조회와 실패 정책만 다룬다 — 어떤 provider의 해석 실패를 삼키고 어떤 것을 전파할지는 provider가
 * 아니라 호출 맥락(webhook 앵커인지 선택 연동인지)이 정하기 때문이다.</p>
 */
@Slf4j
@Service
public class ProjectIntegrationService {

    private static final Duration GITHUB_TOKEN_REFRESH_SKEW = Duration.ofMinutes(5);

    private final ProjectIntegrationRepository repository;
    private final SourceCollectorRegistry collectors;
    private final Clock clock;

    @Autowired
    public ProjectIntegrationService(
            ProjectIntegrationRepository repository,
            SourceCollectorRegistry collectors
    ) {
        this(repository, collectors, Clock.systemUTC());
    }

    ProjectIntegrationService(
            ProjectIntegrationRepository repository,
            SourceCollectorRegistry collectors,
            Clock clock
    ) {
        this.repository = repository;
        this.collectors = collectors;
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

    // 단일 provider 수집 요청 해석 (초기 수집 트리거·webhook의 Jira 재해석 경로).
    // 해석 실패는 삼킨다 — 한 provider의 설정 오류가 트리거 자체를 500으로 만들지 않게 한다.
    public Optional<RawFetchRequest> resolveFetchRequest(UUID projectId, CollectionProvider provider) {
        return findIntegration(projectId, provider)
                .flatMap(integration -> collectors.find(provider)
                        .flatMap(collector -> resolveSafely(collector, integration)));
    }

    private Optional<ProjectCollectionContext> buildContext(ProjectIntegrationRepository.IntegrationRow githubMatch) {
        List<ProjectIntegrationRepository.IntegrationRow> integrations =
                repository.findAllByProjectId(githubMatch.projectId());
        Map<CollectionProvider, RawFetchRequest> requests = new EnumMap<>(CollectionProvider.class);

        for (ProjectIntegrationRepository.IntegrationRow integration : integrations) {
            CollectionProvider provider = CollectionProvider.find(integration.provider()).orElse(null);
            if (provider == null) {
                log.warn("Unsupported integration provider: projectId={}, provider={}",
                        integration.projectId(), integration.provider());
                continue;
            }
            SourceCollector collector = collectors.find(provider).orElse(null);
            if (collector == null) {
                log.warn("No SourceCollector registered: projectId={}, provider={}",
                        integration.projectId(), provider.value());
                continue;
            }

            // GitHub은 webhook 앵커라 해석 실패를 삼키지 않는다 — 설정 오류를 조용히 넘기면
            // "연동 없음"으로 오인돼 수집이 영구히 멈춘 걸 아무도 모른다.
            // 나머지는 선택 연동이라 실패해도 가능한 provider만으로 수집을 진행한다.
            Optional<RawFetchRequest> request = provider == CollectionProvider.GITHUB
                    ? collector.resolveFetchRequest(integration)
                    : resolveSafely(collector, integration);
            request.ifPresent(value -> requests.put(provider, value));
        }

        if (!requests.containsKey(CollectionProvider.GITHUB)) {
            return Optional.empty();
        }
        return Optional.of(new ProjectCollectionContext(githubMatch.projectId().toString(), requests));
    }

    private Optional<RawFetchRequest> resolveSafely(
            SourceCollector collector,
            ProjectIntegrationRepository.IntegrationRow integration
    ) {
        try {
            return collector.resolveFetchRequest(integration);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("Skipping invalid integration: projectId={}, provider={}",
                    integration.projectId(), collector.provider().value(), exception);
            return Optional.empty();
        }
    }

    private Optional<ProjectIntegrationRepository.IntegrationRow> findIntegration(
            UUID projectId,
            CollectionProvider provider
    ) {
        return repository.findAllByProjectId(projectId).stream()
                .filter(integration -> provider.value().equals(integration.provider()))
                .findFirst();
    }

    // token이 없거나 만료 5분 이내면 backend에 갱신을 위임한다 — 수집 도중 만료되는 것을 막는 여유분이다.
    private boolean requiresGitHubTokenRefresh(ProjectIntegrationRepository.IntegrationRow integration) {
        return integration.encryptedInstallationToken() == null
                || integration.installationTokenExpiresAt() == null
                || !integration.installationTokenExpiresAt()
                .isAfter(Instant.now(clock).plus(GITHUB_TOKEN_REFRESH_SKEW));
    }
}
