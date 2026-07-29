package com.history.backend.integration.controller;

import java.net.URI;
import java.util.UUID;

import com.history.backend.integration.dto.AuthorizeUrlResponse;
import com.history.backend.integration.service.IntegrationOAuthService;
import com.history.backend.integration.service.OAuthCallbackOutcome;
import com.history.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Slack OAuth 동의 URL 발급(JWT)과 콜백 복귀(permitAll) — 두 경로의 base가 달라
// 클래스 레벨 @RequestMapping 없이 메서드에 전체 경로를 쓴다.
@RestController
@RequiredArgsConstructor
public class IntegrationOAuthController {

    private final IntegrationOAuthService integrationOAuthService;

    @GetMapping("/api/v1/projects/{projectId}/integrations/slack/authorize")
    public AuthorizeUrlResponse authorizeSlack(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return new AuthorizeUrlResponse(
                integrationOAuthService.buildSlackAuthorizeUrl(authenticatedUser.id(), projectId)
        );
    }

    @GetMapping("/api/v1/integrations/slack/callback")
    public ResponseEntity<Void> callbackSlack(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) {
        OAuthCallbackOutcome outcome = integrationOAuthService.completeSlackCallback(code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(buildRedirectPath(outcome)))
                .build();
    }

    @GetMapping("/api/v1/projects/{projectId}/integrations/jira/authorize")
    public AuthorizeUrlResponse authorizeJira(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return new AuthorizeUrlResponse(
                integrationOAuthService.buildJiraAuthorizeUrl(authenticatedUser.id(), projectId)
        );
    }

    @GetMapping("/api/v1/integrations/jira/callback")
    public ResponseEntity<Void> callbackJira(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) {
        OAuthCallbackOutcome outcome = integrationOAuthService.completeJiraCallback(code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(buildRedirectPath(outcome)))
                .build();
    }

    // 에러 경로에 provider를 함께 실어 프론트가 provider별 실패 문구를 조립할 수 있게 한다
    // (provider 정보가 없으면 Jira 실패에도 Slack 문구가 뜨는 문제가 있었다).
    private String buildRedirectPath(OAuthCallbackOutcome outcome) {
        if (outcome.projectId() == null) {
            return "/?error=" + outcome.errorCode() + "&provider=" + outcome.provider();
        }
        if (outcome.errorCode() != null) {
            return "/projects/" + outcome.projectId() + "/sources?error=" + outcome.errorCode()
                    + "&provider=" + outcome.provider();
        }
        // Jira 자동 복원 완료는 confirmed=true로 넘어온다 — 배너가 "선택하세요" 대신 "복원됐어요"를 보여준다.
        String query = "connected=" + outcome.provider() + (outcome.confirmed() ? "&restored=true" : "");
        return "/projects/" + outcome.projectId() + "/sources?" + query;
    }
}
