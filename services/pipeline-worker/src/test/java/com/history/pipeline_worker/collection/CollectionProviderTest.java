package com.history.pipeline_worker.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionProviderTest {

    @Test
    void value_returnsCanonicalLowercaseProviderString() {
        assertThat(CollectionProvider.GITHUB.value()).isEqualTo("github");
        assertThat(CollectionProvider.JIRA.value()).isEqualTo("jira");
        assertThat(CollectionProvider.SLACK.value()).isEqualTo("slack");
        assertThat(CollectionProvider.DISCORD.value()).isEqualTo("discord");
        assertThat(CollectionProvider.GOOGLE_CHAT.value()).isEqualTo("google-chat");
    }

    @Test
    void find_returnsProviderForKnownValue() {
        assertThat(CollectionProvider.find("github")).contains(CollectionProvider.GITHUB);
        assertThat(CollectionProvider.find("jira")).contains(CollectionProvider.JIRA);
        assertThat(CollectionProvider.find("slack")).contains(CollectionProvider.SLACK);
        assertThat(CollectionProvider.find("discord")).contains(CollectionProvider.DISCORD);
        assertThat(CollectionProvider.find("google-chat")).contains(CollectionProvider.GOOGLE_CHAT);
    }

    // DB row 처리용 — 미지원/대소문자 불일치/null은 조용히 empty (구버전 worker 호환)
    @Test
    void find_returnsEmptyForUnknownValue() {
        assertThat(CollectionProvider.find("notion")).isEmpty();
        assertThat(CollectionProvider.find("GITHUB")).isEmpty();
        assertThat(CollectionProvider.find(null)).isEmpty();
    }

    @Test
    void fromPath_returnsProviderForKnownValue() {
        assertThat(CollectionProvider.fromPath("slack")).isEqualTo(CollectionProvider.SLACK);
    }

    // URL 경로 변수용 — 미지원이면 예외 (컨트롤러가 400으로 변환)
    @Test
    void fromPath_throwsForUnsupportedValue() {
        assertThatThrownBy(() -> CollectionProvider.fromPath("GITHUB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported collection provider");
    }
}
