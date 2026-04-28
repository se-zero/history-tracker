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
import java.util.ArrayList;
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

    public List<NormalizedEvent> normalizeGitHub(RawFetchRequest request) {
        Map<String, Object> raw = gitHubRawService.fetch(request);

        List<NormalizedEvent> events = new ArrayList<>();
        events.addAll(gitHubNormalizer.normalizeCommits(getList(raw, "commits")));
        events.addAll(gitHubNormalizer.normalizePullRequests(getList(raw, "pullRequests")));
        events.addAll(gitHubNormalizer.normalizeIssues(getList(raw, "issues")));

        int published = eventPublisher.publishAll(events);
        updateGitHubCheckpoints(events);
        log.info("GitHub 이벤트 발행: {}", published);

        return events;
    }

    public List<NormalizedEvent> normalizeJira(RawFetchRequest request) {
        Map<String, Object> raw = jiraRawService.fetch(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> searchResult = (Map<String, Object>) raw.get("search");

        List<NormalizedEvent> events = jiraNormalizer.normalizeIssues(searchResult);
        int published = eventPublisher.publishAll(events);
        maxOccurredAt(events).ifPresent(checkpointManager::updateJira);
        log.info("Jira 이벤트 발행: {}", published);

        return events;
    }

    public List<NormalizedEvent> normalizeSlack(RawFetchRequest request) {
        Map<String, Object> raw = slackRawService.fetch(request);
        List<NormalizedEvent> events = slackNormalizer.normalizeChannels(raw);
        int published = eventPublisher.publishAll(events);
        maxOccurredAt(events).ifPresent(checkpointManager::updateSlack);
        log.info("Slack 이벤트 발행: {}", published);

        return events;
    }

    private void updateGitHubCheckpoints(List<NormalizedEvent> events) {
        maxOccurredAtBySourceAndNodeType(events, "GITHUB", "ChangeSet")
                .ifPresent(checkpointManager::updateGitHubCommits);
        maxOccurredAtBySourceAndNodeType(events, "GITHUB", "PullRequest")
                .ifPresent(checkpointManager::updateGitHubPullRequests);
        maxOccurredAtBySourceAndNodeType(events, "GITHUB", "Communication")
                .ifPresent(checkpointManager::updateGitHubIssues);
    }

    private Optional<Instant> maxOccurredAtBySourceAndNodeType(
            List<NormalizedEvent> events,
            String source,
            String nodeType
    ) {
        return events.stream()
                .filter(event -> source.equals(event.source()))
                .filter(event -> nodeType.equals(event.nodeType()))
                .map(NormalizedEvent::occurredAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo);
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
