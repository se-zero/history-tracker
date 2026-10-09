package com.history.backend.oauth.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
    @DisplayName("만료 연결을 먼저 지운 다음 안 쓰는 앱을 지운다 — 순서가 뒤집히면 방금 만료된 연결의 앱이 하루 더 남는다")
    void purgeExpiredAuthorizationsPurgesUnusedClientsAfterExpiredAuthorizations() {
        when(oAuthGrantService.purgeExpired()).thenReturn(4);
        when(oAuthGrantService.purgeUnusedClients()).thenReturn(2);

        new OAuthAuthorizationPurgeScheduler(oAuthGrantService).purgeExpiredAuthorizations();

        InOrder inOrder = inOrder(oAuthGrantService);
        inOrder.verify(oAuthGrantService).purgeExpired();
        inOrder.verify(oAuthGrantService).purgeUnusedClients();
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

    @Test
    @DisplayName("만료 연결 정리가 실패하면 안 쓰는 앱 정리는 부르지 않고 예외 전파")
    void purgeExpiredAuthorizationsSkipsUnusedClientPurgeWhenExpiredPurgeFails() {
        IllegalStateException failure = new IllegalStateException("db down");
        when(oAuthGrantService.purgeExpired()).thenThrow(failure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new OAuthAuthorizationPurgeScheduler(oAuthGrantService).purgeExpiredAuthorizations());

        assertSame(failure, thrown);
        verify(oAuthGrantService, never()).purgeUnusedClients();
    }

    @Test
    @DisplayName("안 쓰는 앱 정리가 실패해도 삼키지 않고 그대로 전파")
    void purgeExpiredAuthorizationsPropagatesUnusedClientPurgeFailure() {
        IllegalStateException failure = new IllegalStateException("db down");
        when(oAuthGrantService.purgeExpired()).thenReturn(4);
        when(oAuthGrantService.purgeUnusedClients()).thenThrow(failure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new OAuthAuthorizationPurgeScheduler(oAuthGrantService).purgeExpiredAuthorizations());

        assertSame(failure, thrown);
    }
}
