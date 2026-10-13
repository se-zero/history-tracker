package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BetaPeriod: 베타 기간 무료 Pro 종료 시각 판정")
class BetaPeriodTest {

    private static final Instant ENDS_AT = Instant.parse("2027-01-12T14:59:59Z");

    @Test
    @DisplayName("빈 문자열 → 베타 없음")
    void blankValueMeansNoBeta() {
        BetaPeriod betaPeriod = betaPeriod("", ENDS_AT.minusSeconds(3600));

        assertThat(betaPeriod.isActive()).isFalse();
        assertThat(betaPeriod.activeEndsAt()).isNull();
    }

    @Test
    @DisplayName("null → 베타 없음")
    void nullValueMeansNoBeta() {
        BetaPeriod betaPeriod = betaPeriod(null, ENDS_AT.minusSeconds(3600));

        assertThat(betaPeriod.isActive()).isFalse();
        assertThat(betaPeriod.activeEndsAt()).isNull();
    }

    @Test
    @DisplayName("공백뿐인 값 → 베타 없음 (파싱 예외도 아니다)")
    void whitespaceOnlyValueMeansNoBeta() {
        BetaPeriod betaPeriod = betaPeriod("   ", ENDS_AT.minusSeconds(3600));

        assertThat(betaPeriod.isActive()).isFalse();
        assertThat(betaPeriod.activeEndsAt()).isNull();
    }

    @Test
    @DisplayName("Z 형식 종료 시각 파싱")
    void parsesUtcOffsetFormat() {
        BetaPeriod betaPeriod = betaPeriod("2027-01-12T14:59:59Z", ENDS_AT.minusSeconds(1));

        assertThat(betaPeriod.activeEndsAt()).isEqualTo(ENDS_AT);
    }

    @Test
    @DisplayName("+09:00 형식도 같은 Instant로 파싱된다")
    void parsesNonUtcOffsetFormatToSameInstant() {
        BetaPeriod betaPeriod = betaPeriod("2027-01-12T23:59:59+09:00", ENDS_AT.minusSeconds(1));

        assertThat(betaPeriod.activeEndsAt()).isEqualTo(ENDS_AT);
    }

    @Test
    @DisplayName("종료 1초 전 → 활성, activeEndsAt은 종료 시각")
    void isActiveOneSecondBeforeEnd() {
        BetaPeriod betaPeriod = betaPeriod("2027-01-12T14:59:59Z", ENDS_AT.minusSeconds(1));

        assertThat(betaPeriod.isActive()).isTrue();
        assertThat(betaPeriod.activeEndsAt()).isEqualTo(ENDS_AT);
    }

    @Test
    @DisplayName("경계: 종료 시각 정각 → 비활성 (now < endsAt, 같은 시각은 이미 종료)")
    void isNotActiveExactlyAtEnd() {
        BetaPeriod betaPeriod = betaPeriod("2027-01-12T14:59:59Z", ENDS_AT);

        assertThat(betaPeriod.isActive()).isFalse();
        assertThat(betaPeriod.activeEndsAt()).isNull();
    }

    @Test
    @DisplayName("종료 후 → 비활성, activeEndsAt은 null")
    void isNotActiveAfterEnd() {
        BetaPeriod betaPeriod = betaPeriod("2027-01-12T14:59:59Z", ENDS_AT.plusSeconds(1));

        assertThat(betaPeriod.isActive()).isFalse();
        assertThat(betaPeriod.activeEndsAt()).isNull();
    }

    @Test
    @DisplayName("형식이 틀린 값 → IllegalArgumentException (기동 시점에 잘못된 설정을 드러낸다)")
    void rejectsMalformedValue() {
        assertThatThrownBy(() -> betaPeriod("not-a-date", ENDS_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("오프셋이 없는 날짜시각 → IllegalArgumentException (시간대를 추측하지 않는다)")
    void rejectsValueWithoutOffset() {
        assertThatThrownBy(() -> betaPeriod("2027-01-12T23:59:59", ENDS_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BetaPeriod betaPeriod(String endsAt, Instant now) {
        return new BetaPeriod(endsAt, Clock.fixed(now, ZoneOffset.UTC));
    }
}
