package com.history.backend.integration.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.dto.CompleteSelectionRequest;
import com.history.backend.integration.dto.ConnectGitHubIntegrationRequest;
import com.history.backend.integration.dto.ConnectSlackIntegrationRequest;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.dto.SelectionOptionResponse;
import com.history.backend.integration.dto.SelectionStepResponse;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/integrations")
public class IntegrationController {

    private final IntegrationService integrationService;

    @GetMapping
    public List<IntegrationResponse> listIntegrations(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return integrationService.listIntegrations(authenticatedUser.id(), projectId);
    }

    @PostMapping("/github")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationResponse connectGitHubRepository(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody ConnectGitHubIntegrationRequest request
    ) {
        return IntegrationResponse.from(integrationService.connectGitHubRepository(
                authenticatedUser.id(),
                projectId,
                request.installationId(),
                request.repositoryId(),
                request.repositoryFullName(),
                request.branch()
        ));
    }

    @PostMapping("/slack")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationResponse connectSlackWorkspace(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody ConnectSlackIntegrationRequest request
    ) {
        return IntegrationResponse.from(integrationService.connectSlackWorkspace(
                authenticatedUser.id(),
                projectId,
                request.token()
        ));
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable String provider
    ) {
        integrationService.disconnect(authenticatedUser.id(), projectId, parseProvider(provider));
    }

    // 알 수 없는 provider를 500이 아니라 400으로 — fromValue가 던지는 IllegalArgumentException은
    // GlobalExceptionHandler에 매핑이 없다.
    private IntegrationProvider parseProvider(String provider) {
        try {
            return IntegrationProvider.fromValue(provider);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
    }

    // provider가 선언한 선택 단계 — 프론트는 단계 수·이름을 하드코딩하지 않고 이 응답으로 화면을 만든다
    @GetMapping("/{provider}/selection/steps")
    public List<SelectionStepResponse> selectionSteps(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable String provider
    ) {
        return integrationService.selectionSteps(authenticatedUser.id(), projectId, parseProvider(provider)).stream()
                .map(SelectionStepResponse::from)
                .toList();
    }

    // 한 단계의 후보 조회. 앞선 단계에서 고른 값은 쿼리 파라미터로 함께 온다(자식 목록에 부모 id가 필요하다).
    @GetMapping("/{provider}/selection/options")
    public List<SelectionOptionResponse> selectionOptions(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable String provider,
            @RequestParam String step,
            @RequestParam Map<String, String> queryParams
    ) {
        Map<String, String> selected = new LinkedHashMap<>(queryParams);
        selected.remove("step");
        return integrationService.selectionOptions(
                        authenticatedUser.id(), projectId, parseProvider(provider), step, selected).stream()
                .map(SelectionOptionResponse::from)
                .toList();
    }

    @PostMapping("/{provider}/selection")
    public IntegrationResponse completeSelection(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable String provider,
            @Valid @RequestBody CompleteSelectionRequest request
    ) {
        IntegrationProvider integrationProvider = parseProvider(provider);
        Map<String, IntegrationService.SelectionInput> submitted = request.selections().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new IntegrationService.SelectionInput(
                                entry.getValue().value(), entry.getValue().label())));
        return IntegrationResponse.from(integrationService.completeSelection(
                authenticatedUser.id(),
                projectId,
                integrationProvider,
                integrationService.buildSelectionExternalRef(integrationProvider, submitted)
        ));
    }
}
