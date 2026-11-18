package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.alert.OperatorAlert;
import com.history.pipeline_worker.outbox.OutboxRepository.Backlog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 보낼 수 있어야 할 미발행 행 가운데 가장 오래된 것의 나이가 기준을 넘으면 운영자에게 알린다.
 *
 * <p>릴레이가 행에 실패를 기록하지 않는 상황 — 브로커 장애, 브로커가 보내는 쪽을 막은 상태, 릴레이 정지 —
 * 은 행 실패 알림({@code outbox_row_stuck})이 잡지 못해서 따로 둔다.
 * 플래그({@code app.outbox.relay.enabled})를 릴레이와 공유한다 — {@code OutboxCleanup}처럼 DB 없는 테스트 컨텍스트에서 함께 꺼야 해서다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxBacklogMonitor {

    static final Duration THRESHOLD = Duration.ofMinutes(10);

    private final OutboxRepository outbox;
    private final OperatorAlert operatorAlert;
    private final Clock clock;

    public OutboxBacklogMonitor(OutboxRepository outbox, OperatorAlert operatorAlert, Clock clock) {
        this.outbox = outbox;
        this.operatorAlert = operatorAlert;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void check() {
        try {
            Backlog backlog = outbox.backlog();
            if (backlog.oldest() == null) return;
            Duration age = Duration.between(backlog.oldest(), clock.instant());
            if (age.compareTo(THRESHOLD) > 0) {
                operatorAlert.notify("outbox_backlog", "아웃박스에 발행되지 않은 행이 쌓여 있습니다: 가장 오래된 행 "
                        + age.toMinutes() + "분 전, 미발행 " + backlog.pending() + "건");
            }
        } catch (RuntimeException e) {
            log.warn("아웃박스 적체 점검 실패 — 다음 주기에 재시도: {}", e.toString());
        }
    }
}
