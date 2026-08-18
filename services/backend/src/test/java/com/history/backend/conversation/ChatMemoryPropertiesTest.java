package com.history.backend.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChatMemoryProperties: 대화 메모리 설정 유효성 검사")
class ChatMemoryPropertiesTest {

    @Test
    @DisplayName("정상 값이면 생성 성공")
    void constructsWithValidValues() {
        ChatMemoryProperties properties = new ChatMemoryProperties(4000, 8000, 3, 5);

        assertThat(properties.historyBudgetChars()).isEqualTo(4000);
        assertThat(properties.summaryTriggerChars()).isEqualTo(8000);
        assertThat(properties.summaryFailureThreshold()).isEqualTo(3);
        assertThat(properties.summarySkipTurns()).isEqualTo(5);
    }

    @Test
    @DisplayName("historyBudgetChars가 0이면 IllegalArgumentException 발생")
    void rejectsZeroHistoryBudgetChars() {
        assertThatThrownBy(() -> new ChatMemoryProperties(0, 8000, 3, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("summaryTriggerChars가 0이면 IllegalArgumentException 발생")
    void rejectsZeroSummaryTriggerChars() {
        assertThatThrownBy(() -> new ChatMemoryProperties(4000, 0, 3, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("summaryFailureThreshold가 0이면 IllegalArgumentException 발생")
    void rejectsZeroSummaryFailureThreshold() {
        assertThatThrownBy(() -> new ChatMemoryProperties(4000, 8000, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("summarySkipTurns가 0이면 IllegalArgumentException 발생")
    void rejectsZeroSummarySkipTurns() {
        assertThatThrownBy(() -> new ChatMemoryProperties(4000, 8000, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 값이면 IllegalArgumentException 발생")
    void rejectsNegativeValue() {
        assertThatThrownBy(() -> new ChatMemoryProperties(-1, 8000, 3, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
