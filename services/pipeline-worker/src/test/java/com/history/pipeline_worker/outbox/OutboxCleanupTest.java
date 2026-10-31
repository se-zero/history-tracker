package com.history.pipeline_worker.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxCleanup: 발행 완료 후 보존 기간(1일)이 지난 아웃박스 행을 삭제. OutboxRepository는 Mock.
 */
@ExtendWith(MockitoExtension.class)
class OutboxCleanupTest {

    @Mock
    private OutboxRepository outbox;

    @Test
    @DisplayName("cutoff는 지금으로부터 1일 전")
    void sweep_passesCutoffOneDayAgo() {
        Instant before = Instant.now();

        cleanup().sweep();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).deletePublishedBefore(cutoff.capture());
        Duration day = Duration.ofDays(1);
        assertThat(cutoff.getValue()).isBetween(before.minus(day).minusSeconds(5), after.minus(day));
    }

    @Test
    @DisplayName("삭제 0건이어도 정상 종료")
    void sweep_zeroDeleted_completesNormally() {
        when(outbox.deletePublishedBefore(any(Instant.class))).thenReturn(0);
        OutboxCleanup cleanup = cleanup();

        assertThatCode(cleanup::sweep).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DB 예외가 나도 전파하지 않는다(스케줄러 스레드 보호)")
    void sweep_dataAccessException_isSwallowed() {
        when(outbox.deletePublishedBefore(any(Instant.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        OutboxCleanup cleanup = cleanup();

        assertThatCode(cleanup::sweep).doesNotThrowAnyException();
    }

    private OutboxCleanup cleanup() {
        return new OutboxCleanup(outbox);
    }
}
