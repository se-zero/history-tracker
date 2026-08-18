package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.history.backend.conversation.ChatMemoryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SummaryBackoffTracker: 요약 연속 실패에 따른 인메모리 스킵 백오프")
class SummaryBackoffTrackerTest {

    private static final UUID CONVERSATION_ID = UUID.fromString("7dbd88a3-807d-4b6d-8fef-462de48f6c6c");

    @Test
    @DisplayName("연속 실패가 임계(3)에 도달하면 스킵 횟수(3)만큼 발동 후 소진되면 스킵 해제")
    void recordFailureTriggersSkipUntilThresholdReachedThenExhausts() {
        SummaryBackoffTracker tracker = tracker(3, 3);

        tracker.recordFailure(CONVERSATION_ID);
        tracker.recordFailure(CONVERSATION_ID);
        tracker.recordFailure(CONVERSATION_ID);

        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isTrue();
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isTrue();
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isTrue();
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isFalse();
    }

    @Test
    @DisplayName("스킵 소진 후에도 계속 실패하면(연속 4회째) 스킵이 다시 발동")
    void recordFailureAfterSkipsExhaustedRetriggersSkip() {
        SummaryBackoffTracker tracker = tracker(3, 3);
        tracker.recordFailure(CONVERSATION_ID);
        tracker.recordFailure(CONVERSATION_ID);
        tracker.recordFailure(CONVERSATION_ID);
        tracker.shouldSkip(CONVERSATION_ID);
        tracker.shouldSkip(CONVERSATION_ID);
        tracker.shouldSkip(CONVERSATION_ID);
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isFalse();

        tracker.recordFailure(CONVERSATION_ID);

        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isTrue();
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isTrue();
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isTrue();
        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isFalse();
    }

    @Test
    @DisplayName("성공 기록은 실패 카운트를 리셋해 이후 실패 1~2회로는 스킵이 발동하지 않음")
    void recordSuccessResetsFailureCount() {
        SummaryBackoffTracker tracker = tracker(3, 3);
        tracker.recordFailure(CONVERSATION_ID);
        tracker.recordFailure(CONVERSATION_ID);

        tracker.recordSuccess(CONVERSATION_ID);

        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isFalse();

        tracker.recordFailure(CONVERSATION_ID);
        tracker.recordFailure(CONVERSATION_ID);

        assertThat(tracker.shouldSkip(CONVERSATION_ID)).isFalse();
    }

    @Test
    @DisplayName("기록이 없는 대화 ID는 스킵하지 않음")
    void shouldSkipReturnsFalseForUnknownConversation() {
        SummaryBackoffTracker tracker = tracker(3, 3);

        assertThat(tracker.shouldSkip(UUID.randomUUID())).isFalse();
    }

    private SummaryBackoffTracker tracker(int failureThreshold, int skipTurns) {
        return new SummaryBackoffTracker(new ChatMemoryProperties(32000, 8000, failureThreshold, skipTurns));
    }
}
