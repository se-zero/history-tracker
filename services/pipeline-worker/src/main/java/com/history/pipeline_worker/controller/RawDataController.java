package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.dto.RawFetchResponse;
import com.history.pipeline_worker.normalizer.GitHubNormalizer;
import com.history.pipeline_worker.normalizer.JiraNormalizer;
import com.history.pipeline_worker.normalizer.SlackNormalizer;
import com.history.pipeline_worker.service.GitHubRawService;
import com.history.pipeline_worker.service.JiraRawService;
import com.history.pipeline_worker.service.SlackRawService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RawDataController {

    private final GitHubRawService gitHubRawService;
    private final JiraRawService jiraRawService;
    private final SlackRawService slackRawService;

    private final GitHubNormalizer gitHubNormalizer;
    private final JiraNormalizer jiraNormalizer;
    private final SlackNormalizer slackNormalizer;

    // ── Raw 수집 엔드포인트 ─────────────────────────────────────

    @PostMapping("/api/v1/raw/github")
    public ResponseEntity<RawFetchResponse> fetchGitHub(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(new RawFetchResponse("GITHUB", Instant.now(), gitHubRawService.fetch(request)));
    }

    @PostMapping("/api/v1/raw/jira")
    public ResponseEntity<RawFetchResponse> fetchJira(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(new RawFetchResponse("JIRA", Instant.now(), jiraRawService.fetch(request)));
    }

    @PostMapping("/api/v1/raw/slack")
    public ResponseEntity<RawFetchResponse> fetchSlack(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(new RawFetchResponse("SLACK", Instant.now(), slackRawService.fetch(request)));
    }

    // ── 정규화 엔드포인트 ───────────────────────────────────────

    @PostMapping("/api/v1/normalize/github")
    public ResponseEntity<List<NormalizedEvent>> normalizeGitHub(@RequestBody @Valid RawFetchRequest request) {
        Map<String, Object> raw = gitHubRawService.fetch(request);

        List<NormalizedEvent> events = new ArrayList<>();
        events.addAll(gitHubNormalizer.normalizeCommits(getList(raw, "commits")));
        events.addAll(gitHubNormalizer.normalizePullRequests(getList(raw, "pullRequests")));
        events.addAll(gitHubNormalizer.normalizeIssues(getList(raw, "issues")));

        return ResponseEntity.ok(events);
    }

    @PostMapping("/api/v1/normalize/jira")
    public ResponseEntity<List<NormalizedEvent>> normalizeJira(@RequestBody @Valid RawFetchRequest request) {
        Map<String, Object> raw = jiraRawService.fetch(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> searchResult = (Map<String, Object>) raw.get("search");

        return ResponseEntity.ok(jiraNormalizer.normalizeIssues(searchResult));
    }

    @PostMapping("/api/v1/normalize/slack")
    public ResponseEntity<List<NormalizedEvent>> normalizeSlack(@RequestBody @Valid RawFetchRequest request) {
        Map<String, Object> raw = slackRawService.fetch(request);
        return ResponseEntity.ok(slackNormalizer.normalizeChannels(raw));
    }

    @SuppressWarnings("unchecked")
    private List<Object> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof List ? (List<Object>) val : List.of();
    }
}
