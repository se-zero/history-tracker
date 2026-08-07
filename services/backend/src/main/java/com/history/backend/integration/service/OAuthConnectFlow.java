package com.history.backend.integration.service;

import com.history.backend.integration.domain.IntegrationProvider;

/**
 * provider별 OAuth 동의 흐름. 새 OAuth 연동을 붙일 때 구현하는 SPI 중 하나다
 * (나머지는 {@link ProviderCredentialLifecycle}, 선택 단계가 있으면 {@link IntegrationSelectionFlow}).
 *
 * <p>state 발급·검증, 에러 코드 매핑, 프론트 복귀 리다이렉트는 {@link IntegrationOAuthService}가,
 * 저장 정책(확정 연동 409 선검사·암호화·pending 처리·수집 트리거)은
 * {@link IntegrationService#connectOAuth}가 provider 공통으로 처리한다. 구현체는 provider 프로토콜의
 * 차이(동의 URL 파라미터, code 교환)만 담당하는 leaf여야 한다 — {@link IntegrationService}를 다시
 * 참조하면 순환 의존이 된다.</p>
 */
public interface OAuthConnectFlow {

    IntegrationProvider provider();

    /** 동의 화면 URL 조립. state는 이미 발급된 값을 받는다(소유권 확인도 호출부가 끝낸 뒤다). */
    String buildAuthorizeUrl(String state);

    /**
     * code를 저장할 자격증명(+수집 대상 참조)으로 교환한다. 저장은 하지 않는다.
     *
     * <p>선택 단계를 선언한 provider는 {@link OAuthConnection#pendingSelection}을 돌려준다 —
     * 동의 시점엔 무엇을 수집할지 아직 모르기 때문이다.</p>
     */
    OAuthConnection exchangeCode(String code);
}
