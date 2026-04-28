package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.QueuedResponse;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.service.PipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    @PostMapping("/api/v1/normalize/github")
    public ResponseEntity<List<NormalizedEvent>> normalizeGitHub(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(pipelineService.normalizeGitHub(request));
    }

    @PostMapping("/api/v1/normalize/jira")
    public ResponseEntity<QueuedResponse> normalizeJira(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.accepted().body(new QueuedResponse(pipelineService.normalizeJira(request)));
    }

    @PostMapping("/api/v1/normalize/slack")
    public ResponseEntity<List<NormalizedEvent>> normalizeSlack(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.ok(pipelineService.normalizeSlack(request));
    }
}
