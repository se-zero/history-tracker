package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProviderCredentialLifecycle: 폐기 SPI 계약")
class ProviderCredentialLifecycleTest {

    @Test
    @DisplayName("revoke에 기본(no-op) 구현이 없어야 한다 — 지원 안 함과 깜빡함을 구분하기 위함")
    void revokeMustNotHaveDefaultImplementation() throws NoSuchMethodException {
        Method revoke = ProviderCredentialLifecycle.class.getMethod("revoke", byte[].class);

        // 기본 no-op이 있으면 폐기 구현을 빠뜨린 provider가 조용히 통과한다 — AccessTokenRefresher를
        // 분리할 때 세운 원칙(기본값 금지)이 폐기 쪽에도 유지되는지 고정한다.
        assertThat(revoke.isDefault()).isFalse();
    }
}
