package com.history.pipeline_worker.checkpoint;

import com.history.pipeline_worker.dto.NormalizedEvent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// 발행한 이벤트 배치에서 checkpoint 전진 값을 계산한다. 기준은 수집 시각이 아니라 occurredAt이다.
public final class CursorProgress {

    private CursorProgress() {
    }

    public static Optional<Instant> maxOccurredAt(List<NormalizedEvent> events) {
        return events.stream()
                .map(NormalizedEvent::occurredAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo);
    }

    public static Instant later(Instant current, Instant candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.isAfter(current)) return candidate;
        return current;
    }
}
