package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;

/**
 * 만료 임박한 access token 갱신 — **만료되는 토큰을 쓰는 provider만** 구현한다.
 *
 * <p>{@link ProviderCredentialLifecycle}에서 떼어낸 이유: 구현 여부가 곧 "이 provider가 갱신을
 * 지원하는가"의 답이 되게 하기 위함이다. 폐기와 한 인터페이스에 있고 갱신이 기본 no-op이면,
 * 폐기만 있는 provider(Slack)가 등록돼 있다는 이유로 내부 토큰 API에서 조용한 204를 받아
 * 호출부가 갱신됐다고 오인한 채 만료된 토큰으로 수집하게 된다.</p>
 *
 * <p>구현체는 provider 클라이언트에만 의존하는 leaf여야 한다.</p>
 */
public interface AccessTokenRefresher {

    IntegrationProvider provider();

    /** 토큰이 없거나 만료 임박이면 갱신해 저장한다. 이미 충분히 남았으면 아무것도 하지 않는다. */
    void ensureFreshAccessToken(UUID projectId);
}
