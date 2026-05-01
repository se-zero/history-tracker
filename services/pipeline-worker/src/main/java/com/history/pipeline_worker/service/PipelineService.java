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

    public int normalizeGitHub(RawFetchRequest request) {
        Map<String, Object> raw = gitHubRawService.fetch(request);

        List<NormalizedEvent> commitEvents = gitHubNormalizer.normalizeCommits(getList(raw, "commits"));
        int published = eventPublisher.publishAll(commitEvents);
        maxOccurredAt(commitEvents).ifPresent(checkpointManager::updateGitHubCommits);

        List<NormalizedEvent> pullRequestEvents = gitHubNormalizer.normalizePullRequests(getList(raw, "pullRequests"));
        published += eventPublisher.publishAll(pullRequestEvents);
        maxOccurredAt(pullRequestEvents).ifPresent(checkpointManager::updateGitHubPullRequests);

        List<NormalizedEvent> issueEvents = gitHubNormalizer.normalizeIssues(getList(raw, "issues"));
        published += eventPublisher.publishAll(issueEvents);
        maxOccurredAt(issueEvents).ifPresent(checkpointManager::updateGitHubIssues);

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
        Map<String, Object> raw = slackRawService.fetch(request);
        List<NormalizedEvent> events = slackNormalizer.normalizeChannels(raw);
        int published = eventPublisher.publishAll(events);
        maxOccurredAt(events).ifPresent(checkpointManager::updateSlack);
        log.info("Slack 이벤트 발행: {}", published);

        return published;
    }

    private Optional<Instant> maxOccurredAt(List<NormalizedEvent> events) {
        return events.stream()
                .map(NormalizedEvent::occurredAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo);
    }

    @SuppressWarnings("unchecked")
    private List<Object> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof List ? (List<Object>) val : List.of();
    }
}
