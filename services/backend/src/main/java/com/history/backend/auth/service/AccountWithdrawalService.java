package com.history.backend.auth.service;

import java.util.UUID;

import com.history.backend.billing.service.SubscriptionCancellationService;
import org.springframework.stereotype.Service;

// 탈퇴. Paddle 해지를 deactivateUser 트랜잭션 밖에서 끝낸 뒤에만 soft delete 한다.
// 해지가 실패했는데 탈퇴하면 계정은 없어지는데 카드 결제는 계속된다.
@Service
public class AccountWithdrawalService {

    private final UserService userService;
    private final SubscriptionCancellationService subscriptionCancellationService;

    public AccountWithdrawalService(
            UserService userService,
            SubscriptionCancellationService subscriptionCancellationService
    ) {
        this.userService = userService;
        this.subscriptionCancellationService = subscriptionCancellationService;
    }

    public void withdraw(UUID userId) {
        userService.getActiveUser(userId);
        subscriptionCancellationService.cancelLiveSubscriptions(userId);
        userService.deactivateUser(userId);
    }
}
