package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.checkpoint.CursorProgress;
import com.history.pipeline_worker.collection.AuthHeaders;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.collection.SourceCollector;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class GitHubCollector implements SourceCollector {

    private static final String REPOSITORY_FULL_NAME = "repository_full_name";
    private static final String BRANCH = "branch";

    private final GitHubRawService rawService;
    private final GitHubNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;
    private final Clock clock;

    @Autowired
    public GitHubCollector(
            GitHubRawService rawService,
            GitHubNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            CredentialCryptoService credentialCryptoService
    ) {
        this(rawService, normalizer, eventPublisher, checkpointService, credentialCryptoService, Clock.systemUTC());
    }

    // Clock 주입 생성자 — installation token 만료 판정을 테스트에서 고정하기 위한 seam
    public GitHubCollector(
            GitHubRawService rawService,
            GitHubNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            CredentialCryptoService credentialCryptoService,
            Clock clock
    ) {
        this.rawService = rawService;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.checkpointService = checkpointService;
        this.credentialCryptoService = credentialCryptoService;
        this.clock = clock;
    }

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.GITHUB;
    }

    // installation token은 1시간짜리 캐시라 없거나 만료됐으면 이번 수집을 건너뛴다(설정 오류가 아니다).
    // 갱신은 webhook 경로가 backend 내부 API로 처리한다.
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
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
        String repositoryFullName = requiredString(integration.externalRef(), REPOSITORY_FULL_NAME);
        String branch = optionalString(integration.externalRef(), BRANCH);

        Map<String, String> options = new HashMap<>();
        if (branch != null) {
            options.put(BRANCH, branch);
        }
        return Optional.of(new RawFetchRequest(AuthHeaders.bearer(token), repositoryFullName, options));
    }

    @Override
    public int collect(String projectId, RawFetchRequest request) {
        GitHubCheckpoint checkpoint = GitHubCheckpoint.from(checkpointService.loadCursors(projectId, provider()));
        GitHubRawService.GitHubFetchContext context = rawService.prepareFetchContext(request, checkpoint);
        Map<String, String> commitPrNumbers = new HashMap<>();
        int published = 0;
        Instant pullRequestCursor = null;
        Instant commitCursor = null;
        Instant issueCursor = null;

        int pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = rawService.fetchMergedPullRequestPage(context, pageNumber);
            List<NormalizedEvent> pageEvents = normalizer.normalizePullRequests(projectId, page.items());
            published += eventPublisher.publishAll(pageEvents);
            pullRequestCursor = CursorProgress.later(pullRequestCursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));
            commitPrNumbers.putAll(rawService.fetchCommitPrNumbers(context, page.items()));

            if (page.finished()) break;
            pageNumber++;
        }

        pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = rawService.fetchCommitPage(context, pageNumber, commitPrNumbers);
            List<NormalizedEvent> pageEvents = normalizer.normalizeCommits(projectId, page.items());
            published += eventPublisher.publishAll(pageEvents);
            commitCursor = CursorProgress.later(commitCursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));

            if (page.finished()) break;
            pageNumber++;
        }

        // PR 커서는 commit 처리 성공 후에 전진시킨다 — 실패 시 다음 실행이 PR 페이지를 다시 읽어
        // sha → prNumber 매핑을 재구성할 수 있게 하기 위함이다.
        checkpointService.updateCursor(projectId, provider(), GitHubCheckpoint.PULL_REQUESTS_CURSOR, pullRequestCursor);
        checkpointService.updateCursor(projectId, provider(), GitHubCheckpoint.COMMITS_CURSOR, commitCursor);

        pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = rawService.fetchIssuePage(context, pageNumber);
            List<NormalizedEvent> pageEvents = normalizer.normalizeIssues(projectId, page.items());
            published += eventPublisher.publishAll(pageEvents);
            issueCursor = CursorProgress.later(issueCursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));

            if (page.finished()) break;
            pageNumber++;
        }

        checkpointService.updateCursor(projectId, provider(), GitHubCheckpoint.ISSUES_CURSOR, issueCursor);

        log.info("GitHub 이벤트 발행: {}", published);

        return published;
    }

    private String requiredString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing external_ref value: " + key);
    }

    // branch는 과거 연동에는 없을 수 있어 선택값으로 읽는다 (null이면 전체 브랜치 수집)
    private String optionalString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
