package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 표준 마크다운(LLM 답변)을 Slack 고유 문법(mrkdwn)으로 변환한다 — 제목·목록 문법이 없는 대신
// 굵게·기울임·취소선·인라인 코드·링크·인용은 별도 표기를 쓰고, <, >, & 는 항상 이스케이프해야 한다.
@DisplayName("SlackMrkdwnConverter: 마크다운을 Slack mrkdwn으로 변환")
class SlackMrkdwnConverterTest {

    @Test
    @DisplayName("제목(#~######)을 굵은 한 줄로 변환한다")
    void convertHeadingsToBoldLine() {
        String markdown = """
                # 제목1
                ## 제목2
                ###### 제목6""";

        String result = SlackMrkdwnConverter.convert(markdown);

        assertThat(result).isEqualTo("*제목1*\n*제목2*\n*제목6*");
    }

    @Test
    @DisplayName("**굵게**를 *굵게*로 변환한다")
    void convertDoubleAsteriskBoldToSingleAsterisk() {
        String result = SlackMrkdwnConverter.convert("**bold** 텍스트");

        assertThat(result).isEqualTo("*bold* 텍스트");
    }

    @Test
    @DisplayName("__굵게__도 *굵게*로 변환한다")
    void convertDoubleUnderscoreBoldToSingleAsterisk() {
        String result = SlackMrkdwnConverter.convert("__bold__ 텍스트");

        assertThat(result).isEqualTo("*bold* 텍스트");
    }

    @Test
    @DisplayName("-, *, + 글머리를 • 로 변환한다")
    void convertDashAsteriskAndPlusBulletsToBulletChar() {
        String markdown = """
                - 항목1
                * 항목2
                + 항목3""";

        String result = SlackMrkdwnConverter.convert(markdown);

        assertThat(result).isEqualTo("• 항목1\n• 항목2\n• 항목3");
    }

    @Test
    @DisplayName("들여쓴 글머리는 들여쓰기를 보존한 채 변환한다")
    void convertIndentedBulletPreservesIndentation() {
        String result = SlackMrkdwnConverter.convert("  - 항목");

        assertThat(result).isEqualTo("  • 항목");
    }

    @Test
    @DisplayName("번호 목록(1. 항목)은 그대로 둔다")
    void leavesNumberedListUnchanged() {
        String result = SlackMrkdwnConverter.convert("1. 항목");

        assertThat(result).isEqualTo("1. 항목");
    }

    @Test
    @DisplayName("[텍스트](url) 링크를 <url|텍스트> 로 변환한다")
    void convertMarkdownLinkToSlackLinkSyntax() {
        String result = SlackMrkdwnConverter.convert("[문서](https://why-code.com)");

        assertThat(result).isEqualTo("<https://why-code.com|문서>");
    }

    @Test
    @DisplayName("링크 URL 안의 &는 &amp;로 이스케이프된 채로 변환된다")
    void escapesAmpersandInsideLinkUrl() {
        String result = SlackMrkdwnConverter.convert("[문서](https://x.test/a?b=1&c=2)");

        assertThat(result).isEqualTo("<https://x.test/a?b=1&amp;c=2|문서>");
    }

    @Test
    @DisplayName("> 인용은 이스케이프 후 복원을 거쳐도 원래 형태로 유지된다")
    void preservesBlockquotePrefixAfterEscapeRoundTrip() {
        String result = SlackMrkdwnConverter.convert("> feat: 정정");

        assertThat(result).isEqualTo("> feat: 정정");
    }

    @Test
    @DisplayName("<, >, & 는 항상 이스케이프된다")
    void escapesAngleBracketsAndAmpersand() {
        String result = SlackMrkdwnConverter.convert("a < b & c > d");

        assertThat(result).isEqualTo("a &lt; b &amp; c &gt; d");
    }

    @Test
    @DisplayName("펜스 코드블록 안은 이스케이프만 하고 다른 변환은 하지 않는다 (펜스 줄 자체는 그대로)")
    void fencedCodeBlockOnlyEscapesWithoutOtherConversion() {
        String markdown = """
                ```
                **x** <y>
                ```""";

        String result = SlackMrkdwnConverter.convert(markdown);

        assertThat(result).isEqualTo("```\n**x** &lt;y&gt;\n```");
    }

    @Test
    @DisplayName("인라인 코드 스팬 안은 이스케이프만 되고 굵게 등 다른 변환은 적용되지 않는다")
    void inlineCodeSpanPreservedAndEscapedButNotConverted() {
        String result = SlackMrkdwnConverter.convert("`**x** <y>`");

        assertThat(result).isEqualTo("`**x** &lt;y&gt;`");
    }

    @Test
    @DisplayName("~~취소선~~을 ~취소선~으로 변환한다")
    void convertDoubleTildeStrikethroughToSingleTilde() {
        String result = SlackMrkdwnConverter.convert("~~취소선~~ 텍스트");

        assertThat(result).isEqualTo("~취소선~ 텍스트");
    }

    @Test
    @DisplayName("수평선만 있는 줄(---, ***, ___)은 빈 줄로 바뀐다")
    void convertHorizontalRuleLineToEmptyLine() {
        String markdown = """
                ---
                ***
                ___""";

        String result = SlackMrkdwnConverter.convert(markdown);

        assertThat(result).isEqualTo("\n\n");
    }

    @Test
    @DisplayName("표 줄은 이스케이프 외에는 구조를 그대로 유지한다")
    void leavesTableRowStructureUnchangedExceptEscaping() {
        String result = SlackMrkdwnConverter.convert("| a & b | c |");

        assertThat(result).isEqualTo("| a &amp; b | c |");
    }

    @Test
    @DisplayName("줄 수와 줄바꿈을 보존한다 (빈 줄 포함)")
    void preservesLineCountAndNewlines() {
        String markdown = "line1\n\nline3";

        String result = SlackMrkdwnConverter.convert(markdown);

        assertThat(result.split("\n", -1)).hasSize(3);
        assertThat(result).isEqualTo("line1\n\nline3");
    }

    @Test
    @DisplayName("실제 답변 샘플: 제목·글머리·굵게·인용이 한 번에 올바르게 변환된다")
    void convertsRealAnswerSample() {
        String markdown = """
                ## 근거
                - **[pull_request]** 152 · 2026-09-10T07:24:34Z · pr_opened · Junsu Seo
                  > feat: 운영 주체 실명 확정 (HT-178)""";

        String result = SlackMrkdwnConverter.convert(markdown);

        assertThat(result).isEqualTo("""
                *근거*
                • *[pull_request]* 152 · 2026-09-10T07:24:34Z · pr_opened · Junsu Seo
                  > feat: 운영 주체 실명 확정 (HT-178)""");
    }
}
