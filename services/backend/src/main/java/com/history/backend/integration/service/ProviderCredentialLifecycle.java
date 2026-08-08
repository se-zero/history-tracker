package com.history.backend.integration.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;

/**
 * 연동 해제 시 provider 쪽 권한 폐기. 새 연동을 붙일 때 구현하는 SPI 중 하나다
 * (나머지는 {@link OAuthConnectFlow}, 만료 토큰을 쓰면 {@link AccessTokenRefresher},
 * 선택 단계가 있으면 {@link IntegrationSelectionFlow}).
 *
 * <p>폐기 대상이 없는 provider(예: GitHub App 설치는 계정 단위라 유지한다)는 빈을 만들지 않는다 —
 * {@code revoke}가 기본 no-op이라 선언만으로 흡수된다. 구현체는 provider 클라이언트에만 의존하는
 * leaf여야 한다.</p>
 */
public interface ProviderCredentialLifecycle {

    IntegrationProvider provider();

    /**
     * 연동 해제 시 provider 쪽 권한 폐기.
     *
     * <p>{@code externalRef}는 자격증명만으로 폐기가 안 되는 provider를 위해 받는다 — 예를 들어
     * Discord의 의미 있는 폐기는 "봇이 서버를 떠나는 것"({@code DELETE /users/@me/guilds/{guild_id}})이라
     * {@code external_ref.guild_id}가 필요하다. Slack·Jira처럼 자격증명만으로 충분한 provider는
     * 이 인자를 무시하면 된다.</p>
     *
     * <p>실패는 구현이 삼킨다 — 이미 폐기된 토큰이나 provider 장애로 해제가 막히면 사용자가 데이터를
     * 지울 방법을 잃는다.</p>
     */
    default void revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
    }
}
