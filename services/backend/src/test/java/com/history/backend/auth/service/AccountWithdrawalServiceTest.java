package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.billing.service.SubscriptionCancellationService;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountWithdrawalService: 탈퇴 전 구독 해지")
class AccountWithdrawalServiceTest {

    private static final UUID USER_ID = UUID.fromString("d6e4a624-daf3-4561-ac6b-1ca067dd689b");

    @Mock
    private UserService userService;

    @Mock
    private SubscriptionCancellationService subscriptionCancellationService;

    @Test
    @DisplayName("활성 사용자 확인 → 구독 해지 → 탈퇴 순서로 호출한다")
    void withdrawCancelsSubscriptionsBeforeDeactivating() {
        AccountWithdrawalService service = service();
        when(userService.getActiveUser(USER_ID)).thenReturn(activeUser());

        service.withdraw(USER_ID);

        InOrder inOrder = inOrder(userService, subscriptionCancellationService);
        inOrder.verify(userService).getActiveUser(USER_ID);
        inOrder.verify(subscriptionCancellationService).cancelLiveSubscriptions(USER_ID);
        inOrder.verify(userService).deactivateUser(USER_ID);
    }

    @Test
    @DisplayName("Paddle 해지가 실패하면 탈퇴하지 않는다")
    void withdrawDoesNotDeactivateWhenCancellationFails() {
        AccountWithdrawalService service = service();
        when(userService.getActiveUser(USER_ID)).thenReturn(activeUser());
        doThrow(new BadGatewayException("Paddle subscription cancel request failed."))
                .when(subscriptionCancellationService).cancelLiveSubscriptions(USER_ID);

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOf(BadGatewayException.class);
        verify(userService, never()).deactivateUser(USER_ID);
    }

    @Test
    @DisplayName("이미 탈퇴한 사용자는 해지도 탈퇴도 하지 않는다")
    void withdrawRejectsInactiveUserBeforeCancellation() {
        AccountWithdrawalService service = service();
        when(userService.getActiveUser(USER_ID)).thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOf(NotFoundException.class);
        verify(subscriptionCancellationService, never()).cancelLiveSubscriptions(USER_ID);
        verify(userService, never()).deactivateUser(USER_ID);
    }

    private AccountWithdrawalService service() {
        return new AccountWithdrawalService(userService, subscriptionCancellationService);
    }

    private User activeUser() {
        return new User("github", "12345", "owner@example.com", "Owner", null);
    }
}
