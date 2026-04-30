package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.dto.QueuedResponse;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.service.PipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    @PostMapping("/api/v1/normalize/github")
    public ResponseEntity<QueuedResponse> normalizeGitHub(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.accepted().body(new QueuedResponse(pipelineService.normalizeGitHub(request)));
    }

    @PostMapping("/api/v1/normalize/jira")
    public ResponseEntity<QueuedResponse> normalizeJira(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.accepted().body(new QueuedResponse(pipelineService.normalizeJira(request)));
    }

    @PostMapping("/api/v1/normalize/slack")
    public ResponseEntity<QueuedResponse> normalizeSlack(@RequestBody @Valid RawFetchRequest request) {
        return ResponseEntity.accepted().body(new QueuedResponse(pipelineService.normalizeSlack(request)));
    }
}
