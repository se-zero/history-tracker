package com.history.backend.integration.service;

import java.util.Map;

/**
 * OAuth code 교환 결과 — 저장할 자격증명과 수집 대상 참조.
 *
 * <p>저장 정책(확정 연동 409 선검사·암호화·pending 처리·수집 트리거)은 {@link IntegrationService}가
 * 소유한다. {@link OAuthConnectFlow} 구현체는 provider 프로토콜로 얻은 값을 여기에 담아 넘기기만 하면
 * 되고, 공용 코드를 고칠 일이 없다.</p>
 *
 * @param credential  암호화 전 자격증명 평문. 형태는 provider가 정한다 — 토큰 문자열 하나일 수도, 갱신에
 *                    필요한 값을 함께 담은 JSON일 수도 있다(복호화 후 해석도 같은 provider의 몫이다).
 * @param externalRef 수집 대상 참조 — {@code integrations.external_ref}에 그대로 저장돼
 *                    pipeline-worker가 읽는다. 키 이름은 provider가 정한다.
 */
public record OAuthConnection(String credential, Map<String, Object> externalRef) {

    public OAuthConnection {
        externalRef = Map.copyOf(externalRef);
    }

    /**
     * 선택 단계를 선언한 provider의 교환 결과 — 동의 시점엔 무엇을 수집할지 아직 모른다.
     *
     * <p>자격증명만 담은 pending 행으로 저장되고, 사용자가 대상을 고르면
     * {@link IntegrationService#completeSelection}이 확정한다.</p>
     */
    public static OAuthConnection pendingSelection(String credential) {
        return new OAuthConnection(credential, Map.of());
    }
}
