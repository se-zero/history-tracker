package com.history.backend.integration.controller;

import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.AccessTokenRefresherRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// pipeline-worker가 만료 임박한 access token 갱신을 위임하는 내부 API.
// 갱신 주체가 둘이면 회전하는 refresh token이 서로를 무효화하므로 backend가 전담한다.
// 경로의 {provider}는 provider별 라우트를 합친 것이라 기존 URL(.../jira/token)은 그대로다.
@RestController
@RequiredArgsConstructor
public class InternalIntegrationTokenController {

    private final AccessTokenRefresherRegistry accessTokenRefreshers;

    @PostMapping("/api/v1/internal/integrations/{projectId}/{provider}/token")
    public ResponseEntity<Void> ensureAccessToken(
            @PathVariable UUID projectId,
            @PathVariable String provider
    ) {
        requireRefresher(parseProvider(provider)).ensureFreshAccessToken(projectId);
        return ResponseEntity.noContent().build();
    }

    // 갱신 수단이 없는 provider는 조용히 204로 넘기지 않고, 라우트가 없던 것과 같게 404로 알린다 —
    // 호출부가 갱신됐다고 오인한 채 만료된 토큰으로 수집하는 것을 막는다.
    // 판정 기준은 AccessTokenRefresher 등록 여부다. 폐기 등 다른 자격증명 동작이 있다는 이유로
    // 통과시키면 안 된다 — Slack은 폐기만 있고 갱신은 없어서 그 경우 조용한 204를 받았다.
    private AccessTokenRefresher requireRefresher(IntegrationProvider provider) {
        return accessTokenRefreshers.find(provider)
                .orElseThrow(() -> new NotFoundException(
                        provider.displayName() + " does not support access token refresh."));
    }

    private IntegrationProvider parseProvider(String provider) {
        try {
            return IntegrationProvider.fromValue(provider);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException(exception.getMessage());
        }
    }
}
