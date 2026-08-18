package com.history.pipeline_worker.source.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackPacingTest {

    @Test
    @DisplayName("escalate를 여러 번 호출해도 minDelayMs는 최댓값을 유지한다")
    void escalate_keepsMaxAcrossCalls() {
        SlackPacing pacing = new SlackPacing();

        pacing.escalate(SlackPacing.Endpoint.HISTORY, 5000L);
        pacing.escalate(SlackPacing.Endpoint.HISTORY, 3000L);

        assertThat(pacing.minDelayMs(SlackPacing.Endpoint.HISTORY)).isEqualTo(5000L);
    }

    @Test
    @DisplayName("escalate된 적 없으면 minDelayMs는 0이다")
    void minDelayMs_withoutEscalation_returnsZero() {
        SlackPacing pacing = new SlackPacing();

        assertThat(pacing.minDelayMs(SlackPacing.Endpoint.CONVERSATIONS_LIST)).isZero();
    }

    @Test
    @DisplayName("escalate는 endpoint별로 독립적으로 적용된다")
    void escalate_isPerEndpoint() {
        SlackPacing pacing = new SlackPacing();

        pacing.escalate(SlackPacing.Endpoint.HISTORY, 4000L);

        assertThat(pacing.minDelayMs(SlackPacing.Endpoint.REPLIES)).isZero();
    }
}
