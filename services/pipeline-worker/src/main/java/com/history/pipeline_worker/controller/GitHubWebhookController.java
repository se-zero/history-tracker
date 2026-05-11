package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.webhook.GitHubWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final GitHubWebhookService gitHubWebhookService;

    @PostMapping("/api/v1/webhook/github")
    public ResponseEntity<Map<String, String>> handleGitHubWebhook(
            @RequestHeader HttpHeaders headers,
            @RequestBody String payload
    ) {
        GitHubWebhookService.WebhookResult result = gitHubWebhookService.handle(headers, payload);
        HttpStatus status = switch (result.status()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case IGNORED, DUPLICATE -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "status", result.status().name().toLowerCase(),
                "message", result.message()
        ));
    }
}
