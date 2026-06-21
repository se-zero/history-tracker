package com.history.backend.auth.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPurgeScheduler: 스케줄러 위임 검증")
class UserPurgeSchedulerTest {

    @Mock
    private UserPurgeService userPurgeService;

    @Test
    @DisplayName("스케줄러가 UserPurgeService.purgeExpiredUsers 위임 호출")
    void purgeExpiredUsersDelegatesToService() {
        when(userPurgeService.purgeExpiredUsers()).thenReturn(3);

        new UserPurgeScheduler(userPurgeService).purgeExpiredUsers();

        verify(userPurgeService).purgeExpiredUsers();
    }
}
