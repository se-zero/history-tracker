package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaddleSubscriptionStatuses: 구독 상태 집합 (Pro 부여·결제 차단·해지 대상의 단일 출처)")
class PaddleSubscriptionStatusesTest {

    @Test
    @DisplayName("ACTIVE_LIKE는 Pro를 주고 새 결제를 막는 상태 셋이다 — paused는 넣지 않는다")
    void activeLikeIsTheStatusesThatGrantProAndBlockCheckout() {
        assertThat(PaddleSubscriptionStatuses.ACTIVE_LIKE)
                .containsExactlyInAnyOrder("active", "trialing", "past_due");
    }

    @Test
    @DisplayName("INACTIVE_LIKE는 강등 대상 상태 둘이다")
    void inactiveLikeIsTheStatusesThatDowngrade() {
        assertThat(PaddleSubscriptionStatuses.INACTIVE_LIKE)
                .containsExactlyInAnyOrder("canceled", "paused");
    }

    @Test
    @DisplayName("NON_TERMINAL은 ACTIVE_LIKE에 paused를 더한 넷이다 — 재개되면 다시 결제되므로 해지·동기화 대상이다")
    void nonTerminalIsActiveLikePlusPaused() {
        assertThat(PaddleSubscriptionStatuses.NON_TERMINAL)
                .containsExactlyInAnyOrder("active", "trialing", "past_due", "paused");
        assertThat(PaddleSubscriptionStatuses.NON_TERMINAL).containsAll(PaddleSubscriptionStatuses.ACTIVE_LIKE);
    }

    @Test
    @DisplayName("공유 상수라 변경할 수 없다")
    void constantsAreUnmodifiable() {
        assertThatThrownBy(() -> PaddleSubscriptionStatuses.ACTIVE_LIKE.add("frozen"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> PaddleSubscriptionStatuses.INACTIVE_LIKE.add("frozen"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> PaddleSubscriptionStatuses.NON_TERMINAL.add("frozen"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
