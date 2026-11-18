package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.alert.OperatorAlert;
import com.history.pipeline_worker.outbox.OutboxRepository.Backlog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OutboxBacklogMonitor: 보낼 수 있어야 할 행 가운데 가장 오래된 것의 나이가 10분을 넘으면 운영자에게 알린다.
 * OutboxRepository·OperatorAlert는 Mock, 시각은 고정 Clock.
 */
@ExtendWith(MockitoExtension.class)
class OutboxBacklogMonitorTest {

    @Mock
    private OutboxRepository outbox;

    @Mock
    private OperatorAlert operatorAlert;

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("가장 오래된 행이 10분 1초 전 → outbox_backlog 알림 1회, 문구에 나이(분)와 미발행 건수 포함")
    void check_oldestOlderThanThreshold_alertsWithAgeAndCount() {
        when(outbox.backlog()).thenReturn(new Backlog(NOW.minus(Duration.ofMinutes(37)).minusSeconds(1), 1234));

        monitor().check();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(operatorAlert).notify(eq("outbox_backlog"), text.capture());
        assertThat(text.getValue()).contains("37", "1234");
    }

    @Test
    @DisplayName("가장 오래된 행이 10분 1초 전 → 알림 1회")
    void check_justOverThreshold_alerts() {
        when(outbox.backlog()).thenReturn(new Backlog(NOW.minus(Duration.ofMinutes(10)).minusSeconds(1), 5));

        monitor().check();

        verify(operatorAlert).notify(eq("outbox_backlog"), anyString());
    }

    @Test
    @DisplayName("가장 오래된 행이 정확히 10분 전 → 알림 없음(초과일 때만)")
    void check_exactlyThreshold_doesNotAlert() {
        when(outbox.backlog()).thenReturn(new Backlog(NOW.minus(OutboxBacklogMonitor.THRESHOLD), 5));

        monitor().check();

        verifyNoInteractions(operatorAlert);
    }

    @Test
    @DisplayName("가장 오래된 행이 9분 전 → 알림 없음")
    void check_belowThreshold_doesNotAlert() {
        when(outbox.backlog()).thenReturn(new Backlog(NOW.minus(Duration.ofMinutes(9)), 5));

        monitor().check();

        verifyNoInteractions(operatorAlert);
    }

    @Test
    @DisplayName("대상이 0건(oldest null) → 알림 없음")
    void check_noRows_doesNotAlert() {
        when(outbox.backlog()).thenReturn(new Backlog(null, 0));

        monitor().check();

        verify(operatorAlert, never()).notify(anyString(), anyString());
    }

    @Test
    @DisplayName("DB 예외가 나도 전파하지 않고 알림도 없다(스케줄러 스레드 보호)")
    void check_dataAccessException_isSwallowed() {
        when(outbox.backlog()).thenThrow(new DataAccessResourceFailureException("db down"));
        OutboxBacklogMonitor monitor = monitor();

        assertThatCode(monitor::check).doesNotThrowAnyException();

        verifyNoInteractions(operatorAlert);
    }

    private OutboxBacklogMonitor monitor() {
        return new OutboxBacklogMonitor(outbox, operatorAlert, clock);
    }
}
