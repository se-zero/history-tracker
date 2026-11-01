package com.history.pipeline_worker.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 발행 완료된 아웃박스 행을 보관 기간이 지난 뒤 지운다.
 *
 * <p>플래그({@code app.outbox.relay.enabled})를 릴레이와 공유한다 — 둘 다 DB 없는 테스트 컨텍스트에서 함께 꺼야 해서다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanup {

    // 발행 뒤 사본이 필요한 경우는 "접두사 표시 직전에 죽은 배치의 재전송"뿐이라 짧아도 된다.
    // 개인정보(payload)가 DB에 머무는 시간도 줄인다.
    static final Duration RETENTION = Duration.ofDays(1);

    private final OutboxRepository outbox;

    public OutboxCleanup(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void sweep() {
        try {
            int deleted = outbox.deletePublishedBefore(Instant.now().minus(RETENTION));
            if (deleted > 0) {
                log.info("발행 완료된 아웃박스 행 {}건 삭제", deleted);
            }
        } catch (DataAccessException e) {
            log.warn("아웃박스 청소 실패 — 다음 주기에 재시도: {}", e.toString());
        }
    }
}
