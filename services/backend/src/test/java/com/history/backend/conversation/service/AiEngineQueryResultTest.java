package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.history.backend.conversation.service.AiEngineQueryResult.FallbackReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AiEngineQueryResult: 질의 결과와 실패 사유")
class AiEngineQueryResultTest {

    @Test
    @DisplayName("success는 fallback이 아니고 사유가 NONE")
    void successHasNoFallbackAndNoneReason() {
        AiEngineQueryResult result = AiEngineQueryResult.success("답변", Map.of("k", "v"));

        assertThat(result.answer()).isEqualTo("답변");
        assertThat(result.fallback()).isFalse();
        assertThat(result.reason()).isEqualTo(FallbackReason.NONE);
        assertThat(result.structured()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("fallback은 사유가 ERROR")
    void fallbackHasErrorReason() {
        AiEngineQueryResult result = AiEngineQueryResult.fallback("오류");

        assertThat(result.answer()).isEqualTo("오류");
        assertThat(result.fallback()).isTrue();
        assertThat(result.reason()).isEqualTo(FallbackReason.ERROR);
        assertThat(result.structured()).isNull();
    }

    @Test
    @DisplayName("timeout은 fallback이면서 사유가 TIMEOUT")
    void timeoutIsFallbackWithTimeoutReason() {
        AiEngineQueryResult result = AiEngineQueryResult.timeout("시간 초과");

        assertThat(result.answer()).isEqualTo("시간 초과");
        assertThat(result.fallback()).isTrue();
        assertThat(result.reason()).isEqualTo(FallbackReason.TIMEOUT);
        assertThat(result.structured()).isNull();
    }
}
