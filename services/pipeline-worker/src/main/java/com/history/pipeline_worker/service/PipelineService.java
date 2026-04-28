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
        checkpointManager.updateGitHubCommits(Instant.now());
        checkpointManager.updateGitHubPullRequests(Instant.now());
        checkpointManager.updateGitHubIssues(Instant.now());
        log.info("GitHub 이벤트 발행: {}", published);

        return events;
    }

    public List<NormalizedEvent> normalizeJira(RawFetchRequest request) {
        Map<String, Object> raw = jiraRawService.fetch(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> searchResult = (Map<String, Object>) raw.get("search");

        List<NormalizedEvent> events = jiraNormalizer.normalizeIssues(searchResult);
        int published = eventPublisher.publishAll(events);
        checkpointManager.updateJira(Instant.now());
        log.info("Jira 이벤트 발행: {}", published);

        return events;
    }

    public List<NormalizedEvent> normalizeSlack(RawFetchRequest request) {
        Map<String, Object> raw = slackRawService.fetch(request);
        List<NormalizedEvent> events = slackNormalizer.normalizeChannels(raw);
        int published = eventPublisher.publishAll(events);
        checkpointManager.updateSlack(Instant.now());
        log.info("Slack 이벤트 발행: {}", published);

        return events;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof List ? (List<Object>) val : List.of();
    }
}
