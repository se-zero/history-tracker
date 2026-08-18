package com.history.backend.integration.controller;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.AccessTokenRefresherRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    /**
     * 응답은 셋으로 갈리고, <b>둘을 섞으면 안 된다</b>.
     *
     * <ul>
     *   <li>{@code 204} — 갱신 완료(또는 이미 충분히 유효).</li>
     *   <li>{@code 501} — 이 provider에는 갱신 수단이 없다(Slack·Discord·GitHub). <b>능력</b>에 대한
     *       답이라 호출부는 저장된 자격증명 그대로 진행해야 한다.</li>
     *   <li>{@code 404} — 연동 행이 없거나(해제 직후 레이스) 알 수 없는 provider다. <b>리소스</b>가
     *       없다는 답이라 호출부는 이번 수집에서 그 provider를 건너뛰어야 한다.</li>
     * </ul>
     *
     * <p>둘 다 404로 답하던 시절에는 해제 직후 레이스가 "갱신 불필요"로 읽혀, 폐기된 토큰으로 수집을
     * 진행했다. Jira 전용이던 시절에는 Jira에 갱신기가 항상 있어 404의 의미가 하나뿐이라 문제가 없었는데,
     * 갱신기 없는 provider까지 이 API를 쓰면서 같은 코드에 두 뜻이 겹쳤다.</p>
     */
    @PostMapping("/api/v1/internal/integrations/{projectId}/{provider}/token")
    public ResponseEntity<Void> ensureAccessToken(
            @PathVariable UUID projectId,
            @PathVariable String provider
    ) {
        // 갱신 수단이 없는 provider는 조용히 204로 넘기지 않는다 — 호출부가 갱신됐다고 오인한 채
        // 만료된 토큰으로 수집하는 것을 막는다. 판정 기준은 AccessTokenRefresher 등록 여부이며,
        // 폐기 등 다른 자격증명 동작이 있다는 이유로 통과시키면 안 된다(Slack은 폐기만 있다).
        Optional<AccessTokenRefresher> refresher = accessTokenRefreshers.find(parseProvider(provider));
        if (refresher.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
        // 연동 행이 없으면 여기서 NotFoundException(404)이 난다 — 위 501과 구분되는 지점이다.
        refresher.get().ensureFreshAccessToken(projectId);
        return ResponseEntity.noContent().build();
    }

    private IntegrationProvider parseProvider(String provider) {
        try {
            return IntegrationProvider.fromValue(provider);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException(exception.getMessage());
        }
    }
}
