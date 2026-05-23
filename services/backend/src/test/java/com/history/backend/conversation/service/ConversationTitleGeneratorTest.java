package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationTitleGeneratorTest {

    private final ConversationTitleGenerator titleGenerator = new ConversationTitleGenerator();

    @Test
    void createsTitleFromFirstMessage() {
        String title = titleGenerator.fromFirstMessage("  Why did authentication change?  ");

        assertThat(title).isEqualTo("Why did authentication change?");
    }

    @Test
    void collapsesWhitespace() {
        String title = titleGenerator.fromFirstMessage("Why\n did\t authentication   change?");

        assertThat(title).isEqualTo("Why did authentication change?");
    }

    @Test
    void truncatesLongTitle() {
        String title = titleGenerator.fromFirstMessage("a".repeat(100));

        assertThat(title).hasSize(80);
        assertThat(title).isEqualTo("a".repeat(80));
    }

    @Test
    void truncatesByCodePoint() {
        String title = titleGenerator.fromFirstMessage("😀".repeat(100));

        assertThat(title.codePointCount(0, title.length())).isEqualTo(80);
        assertThat(title).isEqualTo("😀".repeat(80));
    }
}
