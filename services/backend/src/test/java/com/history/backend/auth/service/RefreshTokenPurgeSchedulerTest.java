package com.history.backend.auth.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenPurgeScheduler: 스케줄러 위임 검증")
class RefreshTokenPurgeSchedulerTest {

    @Mock
    private RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("스케줄러가 RefreshTokenService.purgeExpiredRefreshTokens 위임 호출")
    void purgeExpiredRefreshTokensDelegatesToService() {
        when(refreshTokenService.purgeExpiredRefreshTokens()).thenReturn(4);

        new RefreshTokenPurgeScheduler(refreshTokenService).purgeExpiredRefreshTokens();

        verify(refreshTokenService).purgeExpiredRefreshTokens();
    }
}
