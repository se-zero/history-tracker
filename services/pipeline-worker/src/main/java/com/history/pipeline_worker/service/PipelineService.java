package com.history.pipeline_worker.service;

import com.history.pipeline_worker.checkpoint.FileCheckpointManager;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.normalizer.GitHubNormalizer;
import com.history.pipeline_worker.normalizer.JiraNormalizer;
import com.history.pipeline_worker.normalizer.SlackNormalizer;
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
    private final FileCheckpointManager checkpointManager;

    public CollectionResult collectIncremental(ProjectCollectionContext context) {
        int github = normalizeGitHub(toRawFetchRequest(context.github()));
        int jira = context.jira()
                .map(this::toRawFetchRequest)
                .map(this::normalizeJira)
                .orElse(0);
        int slack = context.slack()
                .map(this::toRawFetchRequest)
                .map(this::normalizeSlack)
                .orElse(0);

        return new CollectionResult(github, jira, slack);
    }

    public int normalizeGitHub(RawFetchRequest request) {
        GitHubRawService.GitHubFetchContext context = gitHubRawService.prepareFetchContext(request);
        Map<String, String> commitPrNumbers = new HashMap<>();
        int published = 0;
        Instant pullRequestCheckpoint = null;

        int pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = gitHubRawService.fetchMergedPullRequestPage(context, pageNumber);
            List<NormalizedEvent> pageEvents = gitHubNormalizer.normalizePullRequests(page.items());
            published += eventPublisher.publishAll(pageEvents);
            pullRequestCheckpoint = maxInstant(pullRequestCheckpoint, maxOccurredAt(pageEvents).orElse(null));
            commitPrNumbers.putAll(gitHubRawService.fetchCommitPrNumbers(context, page.items()));

            if (page.finished()) break;
            pageNumber++;
        }

        pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = gitHubRawService.fetchCommitPage(context, pageNumber, commitPrNumbers);
            List<NormalizedEvent> pageEvents = gitHubNormalizer.normalizeCommits(page.items());
            published += eventPublisher.publishAll(pageEvents);
            maxOccurredAt(pageEvents).ifPresent(checkpointManager::updateGitHubCommits);

            if (page.finished()) break;
            pageNumber++;
        }

        if (pullRequestCheckpoint != null) {
            checkpointManager.updateGitHubPullRequests(pullRequestCheckpoint);
        }

        pageNumber = 1;
        while (true) {
            GitHubRawService.GitHubPage page = gitHubRawService.fetchIssuePage(context, pageNumber);
            List<NormalizedEvent> pageEvents = gitHubNormalizer.normalizeIssues(page.items());
            published += eventPublisher.publishAll(pageEvents);
            maxOccurredAt(pageEvents).ifPresent(checkpointManager::updateGitHubIssues);

            if (page.finished()) break;
            pageNumber++;
        }

        log.info("GitHub 이벤트 발행: {}", published);

        return published;
    }

    public int normalizeJira(RawFetchRequest request) {
        JiraRawService.JiraFetchContext context = jiraRawService.prepareFetchContext(request);
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
            List<NormalizedEvent> pageEvents = jiraNormalizer.normalizeIssues(filteredSearchResult);

            int published = eventPublisher.publishAll(pageEvents);
            totalPublished += published;
            maxOccurredAt(pageEvents).ifPresent(checkpointManager::updateJira);

            nextPageToken = page.nextPageToken();
            pageNumber++;
        } while (nextPageToken != null && !nextPageToken.isBlank());

        log.info("Jira 이벤트 발행: {}", totalPublished);

        return totalPublished;
    }

    public int normalizeSlack(RawFetchRequest request) {
        SlackRawService.SlackFetchContext context = slackRawService.prepareFetchContext(request);
        int published = 0;
        Instant checkpoint = null;

        for (Object rawChannel : slackRawService.fetchChannels(context)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) rawChannel;
            String cursor = null;
            do {
                SlackRawService.SlackHistoryPage page = slackRawService.fetchHistoryPage(context, channel, cursor);
                List<NormalizedEvent> pageEvents = slackNormalizer.normalizeChannel(page.channelData());
                published += eventPublisher.publishAll(pageEvents);
                checkpoint = maxInstant(checkpoint, maxOccurredAt(pageEvents).orElse(null));
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());
        }

        if (checkpoint != null) {
            checkpointManager.updateSlack(checkpoint);
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

    public record CollectionResult(int github, int jira, int slack) {}

}
