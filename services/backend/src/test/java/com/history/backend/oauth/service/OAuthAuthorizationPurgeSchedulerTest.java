package com.history.backend.oauth.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthAuthorizationPurgeScheduler: 스케줄러 위임 검증")
class OAuthAuthorizationPurgeSchedulerTest {

    @Mock
    private OAuthGrantService oAuthGrantService;

    @Test
    @DisplayName("스케줄러가 OAuthGrantService.purgeExpired 위임 호출")
    void purgeExpiredAuthorizationsDelegatesToService() {
        when(oAuthGrantService.purgeExpired()).thenReturn(4);

        new OAuthAuthorizationPurgeScheduler(oAuthGrantService).purgeExpiredAuthorizations();

        verify(oAuthGrantService).purgeExpired();
    }

    @Test
    @DisplayName("정리 중 RuntimeException이 나면 삼키지 않고 그대로 전파")
    void purgeExpiredAuthorizationsPropagatesRuntimeException() {
        IllegalStateException failure = new IllegalStateException("db down");
        when(oAuthGrantService.purgeExpired()).thenThrow(failure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new OAuthAuthorizationPurgeScheduler(oAuthGrantService).purgeExpiredAuthorizations());

        assertSame(failure, thrown);
    }
}
