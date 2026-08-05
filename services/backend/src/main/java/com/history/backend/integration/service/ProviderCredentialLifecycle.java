package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;

/**
 * 연동 자격증명의 수명주기 — 폐기와 갱신. 새 연동을 붙일 때 구현하는 두 SPI 중 하나다
 * (나머지 하나는 {@link OAuthConnectFlow}).
 *
 * <p>두 동작 모두 provider마다 있을 수도 없을 수도 있어 기본 구현을 no-op으로 둔다. 구현체는
 * provider 클라이언트에만 의존하는 leaf여야 한다 — {@link IntegrationService}가 이 SPI를 주입받으므로
 * 여기서 다시 IntegrationService를 참조하면 순환 의존이 된다.</p>
 */
public interface ProviderCredentialLifecycle {

    IntegrationProvider provider();

    /**
     * 연동 해제 시 provider 쪽 권한 폐기.
     *
     * <p>실패는 구현이 삼킨다 — 이미 폐기된 토큰이나 provider 장애로 해제가 막히면 사용자가 데이터를
     * 지울 방법을 잃는다. 폐기 대상이 없는 provider(예: GitHub App 설치)는 기본 no-op을 쓴다.</p>
     */
    default void revoke(byte[] encryptedCredential) {
    }

    /**
     * 만료 임박한 access token 갱신 보장 (내부 서비스 API가 호출).
     *
     * <p>비만료 토큰을 쓰는 provider는 기본 no-op을 쓴다.</p>
     */
    default void ensureFreshAccessToken(UUID projectId) {
    }
}
