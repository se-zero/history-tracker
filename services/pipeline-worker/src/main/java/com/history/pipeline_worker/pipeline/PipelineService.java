package com.history.pipeline_worker.pipeline;

import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.checkpoint.ProjectCheckpointData;
import com.history.pipeline_worker.collection.GitHubIntegration;
import com.history.pipeline_worker.collection.JiraIntegration;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.SlackIntegration;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.source.github.GitHubNormalizer;
import com.history.pipeline_worker.source.github.GitHubRawService;
import com.history.pipeline_worker.source.jira.JiraNormalizer;
import com.history.pipeline_worker.source.jira.JiraRawService;
import com.history.pipeline_worker.source.slack.SlackNormalizer;
import com.history.pipeline_worker.source.slack.SlackRawService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final GitHubRawService gitHubRawService;
    private final JiraRawService jiraRawService;
    private final SlackRawService slackRawService;

    private final GitHubNormalizer gitHubNormalizer;
    private final JiraNormalizer jiraNormalizer;
    private final SlackNormalizer slackNormalizer;

    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;

    public CollectionResult collectIncremental(ProjectCollectionContext context) {
        ProjectCheckpointData checkpoints = checkpointService.loadProjectCheckpoints(context.projectId());
        int github = normalizeGitHub(context.projectId(), toRawFetchRequest(context.github()), checkpoints.github);
        int jira = context.jira()
                .map(this::toRawFetchRequest)
                .map(request -> normalizeJira(context.projectId(), request, checkpoints.jira.lastScannedAt))
                .orElse(0);
        int slack = context.slack()
                .map(this::toRawFetchRequest)
                .map(request -> normalizeSlack(context.projectId(), request, checkpoints.slack.lastScannedAt))
                .orElse(0);

        return new CollectionResult(github, jira, slack);
    }

    public int normalizeGitHub(String projectId, RawFetchRequest request) {
        ProjectCheckpointData checkpoints = checkpointService.loadProjectCheckpoints(projectId);
        return normalizeGitHub(projectId, request, checkpoints.github);
    }

    public int normalizeJira(String projectId, RawFetchRequest request) {
        ProjectCheckpointData checkpoints = checkpointService.loadProjectCheckpoints(projectId);
        return normalizeJira(projectId, request, checkpoints.jira.lastScannedAt);
    }

    public int normalizeSlack(String projectId, RawFetchRequest request) {
        ProjectCheckpointData checkpoints = checkpointService.loadProjectCheckpoints(projectId);
        return normalizeSlack(projectId, request, checkpoints.slack.lastScannedAt);
    }

    private int normalizeGitHub(
            String projectId,
            RawFetchRequest request,
            ProjectCheckpointData.GitHubCheckpoint checkpoint
    ) {
        GitHubRawService.GitHubFetchContext context = gitHubRawService.prepareFetchContext(request, checkpoint);
        Map<String, String> commitPrNumbers = new HashMap<>();
        int published = 0;
        Instant pullRequestCheckpoint = null;
        Instant commitCheckpoint = null;
        Instant issueCheckpoint = null;

        int pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = gitHubRawService.fetchMergedPullRequestPage(context, pageNumber);
            List<NormalizedEvent> pageEvents = gitHubNormalizer.normalizePullRequests(projectId, page.items());
            published += eventPublisher.publishAll(pageEvents);
            pullRequestCheckpoint = maxInstant(pullRequestCheckpoint, maxOccurredAt(pageEvents).orElse(null));
            commitPrNumbers.putAll(gitHubRawService.fetchCommitPrNumbers(context, page.items()));

            if (page.finished()) break;
            pageNumber++;
        }

        pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = gitHubRawService.fetchCommitPage(context, pageNumber, commitPrNumbers);
            List<NormalizedEvent> pageEvents = gitHubNormalizer.normalizeCommits(projectId, page.items());
            published += eventPublisher.publishAll(pageEvents);
            commitCheckpoint = maxInstant(commitCheckpoint, maxOccurredAt(pageEvents).orElse(null));

            if (page.finished()) break;
            pageNumber++;
        }

        if (pullRequestCheckpoint != null) {
            checkpointService.updateGitHubPullRequests(projectId, pullRequestCheckpoint);
        }
        if (commitCheckpoint != null) {
            checkpointService.updateGitHubCommits(projectId, commitCheckpoint);
        }

        pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = gitHubRawService.fetchIssuePage(context, pageNumber);
            List<NormalizedEvent> pageEvents = gitHubNormalizer.normalizeIssues(projectId, page.items());
            published += eventPublisher.publishAll(pageEvents);
            issueCheckpoint = maxInstant(issueCheckpoint, maxOccurredAt(pageEvents).orElse(null));

            if (page.finished()) break;
            pageNumber++;
        }

        if (issueCheckpoint != null) {
            checkpointService.updateGitHubIssues(projectId, issueCheckpoint);
        }

        log.info("GitHub 이벤트 발행: {}", published);

        return published;
    }

    private int normalizeJira(String projectId, RawFetchRequest request, Instant lastScannedAt) {
        JiraRawService.JiraFetchContext context = jiraRawService.prepareFetchContext(request, lastScannedAt);
        String nextPageToken = null;
        int totalPublished = 0;
        int pageNumber = 0;

        do {
            JiraRawService.JiraSearchPage page = jiraRawService.fetchSearchPage(context, nextPageToken, pageNumber + 1);
            if (page.limitReached()) {
                log.warn("Jira max pages per run 도달: queued={}", totalPublished);
                break;
            }

            Map<String, Object> filteredSearchResult = page.searchResult();
            List<NormalizedEvent> pageEvents = jiraNormalizer.normalizeIssues(projectId, filteredSearchResult);

            int published = eventPublisher.publishAll(pageEvents);
            totalPublished += published;
            maxOccurredAt(pageEvents).ifPresent(checkpoint -> checkpointService.updateJira(projectId, checkpoint));

            nextPageToken = page.nextPageToken();
            pageNumber++;
        } while (nextPageToken != null && !nextPageToken.isBlank());

        log.info("Jira 이벤트 발행: {}", totalPublished);

        return totalPublished;
    }

    private int normalizeSlack(String projectId, RawFetchRequest request, Instant lastScannedAt) {
        SlackRawService.SlackFetchContext context = slackRawService.prepareFetchContext(request, lastScannedAt);
        int published = 0;
        Instant checkpoint = null;

        for (Object rawChannel : slackRawService.fetchChannels(context)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) rawChannel;
            String cursor = null;
            do {
                SlackRawService.SlackHistoryPage page = slackRawService.fetchHistoryPage(context, channel, cursor);
                List<NormalizedEvent> pageEvents = slackNormalizer.normalizeChannel(projectId, page.channelData());
                published += eventPublisher.publishAll(pageEvents);
                checkpoint = maxInstant(checkpoint, maxOccurredAt(pageEvents).orElse(null));
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());
        }

        if (checkpoint != null) {
            checkpointService.updateSlack(projectId, checkpoint);
        }
        log.info("Slack 이벤트 발행: {}", published);

        return published;
    }

    private Optional<Instant> maxOccurredAt(List<NormalizedEvent> events) {
        return events.stream()
                .map(NormalizedEvent::occurredAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo);
    }

    private Instant maxInstant(Instant current, Instant candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.isAfter(current)) return candidate;
        return current;
    }

    private RawFetchRequest toRawFetchRequest(GitHubIntegration integration) {
        Map<String, String> options = new HashMap<>();
        if (integration.branch() != null && !integration.branch().isBlank()) {
            options.put("branch", integration.branch());
        }
        return new RawFetchRequest(integration.credentials(), integration.repositoryFullName(), options);
    }

    private RawFetchRequest toRawFetchRequest(JiraIntegration integration) {
        Map<String, String> options = new HashMap<>();
        if (integration.baseUrl() != null && !integration.baseUrl().isBlank()) {
            options.put("baseUrl", integration.baseUrl());
        }
        return new RawFetchRequest(integration.credentials(), integration.projectKey(), options);
    }

    private RawFetchRequest toRawFetchRequest(SlackIntegration integration) {
        return new RawFetchRequest(integration.credentials(), null, Map.of());
    }

}
