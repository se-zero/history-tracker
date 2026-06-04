package com.history.backend.auth.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserPurgeSchedulerTest {

    @Mock
    private UserPurgeService userPurgeService;

    @Test
    void purgeExpiredUsersDelegatesToService() {
        when(userPurgeService.purgeExpiredUsers()).thenReturn(3);

        new UserPurgeScheduler(userPurgeService).purgeExpiredUsers();

        verify(userPurgeService).purgeExpiredUsers();
    }
}
