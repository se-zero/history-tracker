package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationTitleGenerator: 대화 제목 생성")
class ConversationTitleGeneratorTest {

    private final ConversationTitleGenerator titleGenerator = new ConversationTitleGenerator();

    @Test
    @DisplayName("첫 메시지로 제목 생성 (앞뒤 공백 제거)")
    void createsTitleFromFirstMessage() {
        String title = titleGenerator.fromFirstMessage("  Why did authentication change?  ");

        assertThat(title).isEqualTo("Why did authentication change?");
    }

    @Test
    @DisplayName("내부 공백 연속 → 단일 공백으로 정규화")
    void collapsesWhitespace() {
        String title = titleGenerator.fromFirstMessage("Why\n did\t authentication   change?");

        assertThat(title).isEqualTo("Why did authentication change?");
    }

    @Test
    @DisplayName("80자 초과 제목 → 80자로 자르기")
    void truncatesLongTitle() {
        String title = titleGenerator.fromFirstMessage("a".repeat(100));

        assertThat(title).hasSize(80);
        assertThat(title).isEqualTo("a".repeat(80));
    }

    @Test
    @DisplayName("이모지 포함 제목 → 코드포인트 기준 80자로 자르기")
    void truncatesByCodePoint() {
        String title = titleGenerator.fromFirstMessage("😀".repeat(100));

        assertThat(title.codePointCount(0, title.length())).isEqualTo(80);
        assertThat(title).isEqualTo("😀".repeat(80));
    }
}
