package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;

/**
 * provider별 OAuth 동의 흐름. 새 OAuth 연동을 붙일 때 구현하는 두 SPI 중 하나다
 * (나머지 하나는 {@link ProviderCredentialLifecycle}).
 *
 * <p>state 발급·검증, 에러 코드 매핑, 프론트 복귀 리다이렉트는 {@link IntegrationOAuthService}가
 * 공통으로 처리하므로, 구현체는 provider 프로토콜의 차이(동의 URL 파라미터, code 교환 후 저장 방식)만
 * 담당한다.</p>
 */
public interface OAuthConnectFlow {

    IntegrationProvider provider();

    /** 동의 화면 URL 조립. state는 이미 발급된 값을 받는다(소유권 확인도 호출부가 끝낸 뒤다). */
    String buildAuthorizeUrl(String state);

    /**
     * code를 교환해 연동을 저장한다.
     *
     * @return 재동의로 이전 선택이 **자동 복원돼** 곧바로 확정됐으면 true. 사용자가 선택 화면을
     *         거쳐야 하거나 선택 단계가 없는 provider는 false — 프론트의 "복원 완료" 배너 조건이라
     *         최초 연결에서 true를 반환하면 안 된다.
     */
    boolean connect(UUID userId, UUID projectId, String code);
}
