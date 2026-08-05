package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// OAuth 동의 URL 조립과 콜백 처리를 오케스트레이션한다 (컨트롤러에 로직을 두지 않기 위한 얇은 계층).
// provider별 차이는 OAuthConnectFlow가 담당하고, 여기서는 state 검증·에러 코드 매핑처럼 모든
// provider가 공유하는 부분만 다룬다.
// 콜백 요청에는 JWT가 없으므로, state 서명이 신원·프로젝트 소유권을 증명하는 유일한 수단이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationOAuthService {

    private final ProjectService projectService;
    private final OAuthStateService oauthStateService;
    private final OAuthConnectFlowRegistry connectFlows;

    // 소유권 확인 후 state를 발급해 동의 화면 URL을 조립한다
    public String buildAuthorizeUrl(UUID userId, UUID projectId, IntegrationProvider provider) {
        OAuthConnectFlow flow = requireFlow(provider);
        projectService.getProject(userId, projectId);
        String state = oauthStateService.issue(projectId, userId, provider.value());

        return flow.buildAuthorizeUrl(state);
    }

    // state를 먼저 검증해 projectId를 복원한 뒤, 동의 거부·연동 실패를 프론트 표시용 에러 코드로 변환한다.
    // state 자체가 위조·만료된 경우에만 projectId 없이 반환한다.
    public OAuthCallbackOutcome completeCallback(
            IntegrationProvider provider,
            String code,
            String state,
            String error
    ) {
        OAuthConnectFlow flow = requireFlow(provider);
        String providerValue = provider.value();

        OAuthStateClaims claims;
        try {
            claims = oauthStateService.verify(state, providerValue);
        } catch (OAuthStateException exception) {
            // projectId를 알 수 없어 사용자에게 배너로 알릴 수 없는 경로 — 로그가 유일한 관측 수단
            log.warn("OAuth callback rejected invalid state. provider={}, reason={}",
                    providerValue, exception.getMessage());
            return new OAuthCallbackOutcome(null, providerValue, "invalid_state", false);
        }

        if (error != null && !error.isBlank()) {
            // provider가 보내는 error는 access_denied 외에도 다양하다(예: invalid_scope).
            // "취소했어요" 문구는 사용자가 실제로 거부한 경우에만 정확하므로 access_denied만 구분한다.
            if ("access_denied".equals(error)) {
                return new OAuthCallbackOutcome(claims.projectId(), providerValue, "access_denied", false);
            }
            log.warn("OAuth callback returned provider error. provider={}, projectId={}, error={}",
                    providerValue, claims.projectId(), error);
            return new OAuthCallbackOutcome(claims.projectId(), providerValue, "connect_failed", false);
        }

        try {
            boolean confirmed = flow.connect(claims.userId(), claims.projectId(), code);
            return new OAuthCallbackOutcome(claims.projectId(), providerValue, null, confirmed);
        } catch (ConflictException exception) {
            return new OAuthCallbackOutcome(claims.projectId(), providerValue, "already_connected", false);
        } catch (RuntimeException exception) {
            log.warn("OAuth callback failed. provider={}, projectId={}, error={}",
                    providerValue, claims.projectId(), exception.getMessage());
            return new OAuthCallbackOutcome(claims.projectId(), providerValue, "connect_failed", false);
        }
    }

    // OAuth 동의로 붙이지 않는 provider(GitHub은 App 설치)는 라우트가 없던 것과 같게 404로 처리한다
    private OAuthConnectFlow requireFlow(IntegrationProvider provider) {
        return connectFlows.find(provider)
                .orElseThrow(() -> new NotFoundException(
                        provider.displayName() + " does not support the OAuth connect flow."));
    }
}
