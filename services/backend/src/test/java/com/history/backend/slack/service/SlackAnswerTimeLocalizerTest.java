package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// clients/web-dashboard/src/lib/remarkLocalTime.ts 의 ISO_INSTANT 정규식·코드블록 규칙을
// backend(Slack 답변)에서 그대로 재현한다 — 두 표시 경로가 같은 시각을 다르게 보여주면 안 된다.
@DisplayName("SlackAnswerTimeLocalizer: 답변 본문의 UTC ISO 시각을 현지 시간으로 변환")
class SlackAnswerTimeLocalizerTest {

    @Test
    @DisplayName("초 단위 UTC(Z) 시각을 Asia/Seoul 현지 시간(한국어 장문)으로 변환")
    void localizeConvertsUtcInstantWithSecondsToKoreanFormatInZone() {
        String markdown = "완료: 2026-09-10T07:24:34Z";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo("완료: 2026년 9월 10일 오후 4:24");
    }

    @Test
    @DisplayName("소수점 초가 있는 UTC(Z) 시각도 변환된다")
    void localizeConvertsUtcInstantWithFractionalSecondsToKoreanFormat() {
        String markdown = "완료: 2026-09-09T05:21:40.07Z";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo("완료: 2026년 9월 9일 오후 2:21");
    }

    @Test
    @DisplayName("오프셋(+09:00) 표기 시각도 대상 zone으로 정확히 변환된다")
    void localizeConvertsOffsetInstantToTargetZone() {
        String markdown = "완료: 2026-09-10T07:24:34+09:00";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo("완료: 2026년 9월 10일 오전 7:24");
    }

    @Test
    @DisplayName("zone이 실제로 적용된다 — 같은 UTC 시각도 America/Los_Angeles면 다른 결과")
    void localizeAppliesGivenZoneNotHardcodedSeoul() {
        String markdown = "완료: 2026-09-10T07:24:34Z";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("America/Los_Angeles"));

        assertThat(result).isEqualTo("완료: 2026년 9월 10일 오전 12:24");
        assertThat(result).isNotEqualTo("완료: 2026년 9월 10일 오후 4:24");
    }

    @Test
    @DisplayName("날짜 단독(2026-09-10)은 instant가 아니므로 변환하지 않는다")
    void localizeLeavesDateOnlyUnchanged() {
        String markdown = "출시일은 2026-09-10 입니다.";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo(markdown);
    }

    @Test
    @DisplayName("펜스 코드블록(```) 안의 시각은 원문 그대로 보존한다")
    void localizeLeavesFencedCodeBlockUnchanged() {
        String markdown = """
                시작 2026-09-10T07:24:34Z
                ```
                2026-09-10T07:24:34Z
                ```""";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).contains("시작 2026년 9월 10일 오후 4:24");
        assertThat(result).contains("```\n2026-09-10T07:24:34Z\n```");
    }

    @Test
    @DisplayName("목록 항목 안에 들여쓴 펜스 코드블록도 펜스로 인식해 원문을 보존한다")
    void localizeLeavesIndentedFencedCodeBlockUnchanged() {
        String markdown = """
                - 커밋 메시지:
                  ```
                  2026-09-10T07:24:34Z
                  ```
                완료 2026-09-10T07:24:34Z""";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).contains("  ```\n  2026-09-10T07:24:34Z\n  ```");
        assertThat(result).contains("완료 2026년 9월 10일 오후 4:24");
    }

    @Test
    @DisplayName("인라인 코드가 통째로 시각 하나면 백틱을 벗기고 변환한다")
    void localizeConvertsInlineCodeThatIsExactlyOneInstant() {
        String markdown = "완료 시각: `2026-09-10T07:24:34Z`";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo("완료 시각: 2026년 9월 10일 오후 4:24");
    }

    @Test
    @DisplayName("인라인 코드에 시각 외 다른 내용이 섞이면 원문 그대로 보존한다")
    void localizeLeavesInlineCodeUnchangedWhenMixedWithOtherText() {
        String markdown = "로그: `at 2026-09-10T07:24:34Z done`";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo(markdown);
    }

    @Test
    @DisplayName("정규식엔 맞지만 파싱 불가능한 값(월/일/시/분 범위 초과)은 원문을 보존한다")
    void localizeLeavesUnparsableValueUnchanged() {
        String markdown = "시각: 2026-13-45T99:99:00Z 확인 필요";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo(markdown);
    }

    @Test
    @DisplayName("한 문단에 시각이 여러 개면 전부 변환되고 사이 텍스트는 보존된다")
    void localizeConvertsAllInstantsInParagraphPreservingSurroundingText() {
        String markdown = "시작 2026-09-10T08:00:00Z 종료 2026-09-10T23:59:00Z 완료";

        String result = SlackAnswerTimeLocalizer.localize(markdown, ZoneId.of("Asia/Seoul"));

        assertThat(result).isEqualTo("시작 2026년 9월 10일 오후 5:00 종료 2026년 9월 11일 오전 8:59 완료");
    }
}
