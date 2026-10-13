package com.history.backend.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 베타 기간(전 FREE 사용자에게 Pro 기능 개방) 판정의 단일 출처. users.plan은 건드리지 않고
// 이 종료 시각 전인지만 본다. 판정을 한 곳에 모아야 한도 검사·API 응답이 서로 어긋나지 않는다.
@Component
public class BetaPeriod {

    private final Instant endsAt;
    private final Clock clock;

    @Autowired
    public BetaPeriod(@Value("${app.plan.beta-ends-at:}") String endsAt) {
        this(endsAt, Clock.systemUTC());
    }

    BetaPeriod(String endsAt, Clock clock) {
        this.endsAt = parse(endsAt);
        this.clock = clock;
    }

    public boolean isActive() {
        return endsAt != null && clock.instant().isBefore(endsAt);
    }

    // 베타 중이면 종료 시각, 아니면 null (API 응답용)
    public Instant activeEndsAt() {
        return isActive() ? endsAt : null;
    }

    // 오프셋 필수 — 시간대를 추측하면 종료가 몇 시간씩 어긋난다. 오타로 베타가 조용히 꺼진 채
    // 배포되지 않도록 잘못된 값은 기동 시점에 예외로 멈춘다.
    private static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "app.plan.beta-ends-at must be ISO-8601 with offset (e.g. 2027-01-12T23:59:59+09:00).", e);
        }
    }
}
