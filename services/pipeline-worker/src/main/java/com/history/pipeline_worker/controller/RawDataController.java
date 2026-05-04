package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.dto.RawFetchResponse;
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

@RestController
@RequiredArgsConstructor
public class RawDataController {

    private final GitHubRawService gitHubRawService;
    private final JiraRawService jiraRawService;
    private final SlackRawService slackRawService;

    // ── Raw 수집 엔드포인트 ─────────────────────────────────────

    @PostMapping("/api/v1/raw/github")
    public ResponseEntity<RawFetchResponse> fetchGitHub(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(new RawFetchResponse("GITHUB", Instant.now(), gitHubRawService.fetchSample(request)));
    }

    @PostMapping("/api/v1/raw/jira")
    public ResponseEntity<RawFetchResponse> fetchJira(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(new RawFetchResponse("JIRA", Instant.now(), jiraRawService.fetchSample(request)));
    }

    @PostMapping("/api/v1/raw/slack")
    public ResponseEntity<RawFetchResponse> fetchSlack(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(new RawFetchResponse("SLACK", Instant.now(), slackRawService.fetchSample(request)));
    }
}
