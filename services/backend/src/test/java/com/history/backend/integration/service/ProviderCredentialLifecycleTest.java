package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProviderCredentialLifecycle: 폐기 SPI 계약")
class ProviderCredentialLifecycleTest {

    @Test
    @DisplayName("revoke에 기본(no-op) 구현이 없어야 한다 — 지원 안 함과 깜빡함을 구분하기 위함")
    void revokeMustNotHaveDefaultImplementation() throws NoSuchMethodException {
        // externalRef 인자는 Discord처럼 자격증명만으로 폐기가 안 되는 provider를 위한 것이다
        // (봇이 서버를 나가려면 guild_id가 필요하다) — 이 계약 테스트와는 무관해 시그니처만 맞춘다.
        Method revoke = ProviderCredentialLifecycle.class.getMethod("revoke", byte[].class, Map.class);

        // 기본 no-op이 있으면 폐기 구현을 빠뜨린 provider가 조용히 통과한다 — AccessTokenRefresher를
        // 분리할 때 세운 원칙(기본값 금지)이 폐기 쪽에도 유지되는지 고정한다.
        assertThat(revoke.isDefault()).isFalse();
    }
}
