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
        assertThat(CollectionProvider.LINEAR.value()).isEqualTo("linear");
        assertThat(CollectionProvider.ASANA.value()).isEqualTo("asana");
        assertThat(CollectionProvider.CLICKUP.value()).isEqualTo("clickup");
    }

    @Test
    void find_returnsProviderForKnownValue() {
        assertThat(CollectionProvider.find("github")).contains(CollectionProvider.GITHUB);
        assertThat(CollectionProvider.find("jira")).contains(CollectionProvider.JIRA);
        assertThat(CollectionProvider.find("slack")).contains(CollectionProvider.SLACK);
        assertThat(CollectionProvider.find("linear")).contains(CollectionProvider.LINEAR);
        assertThat(CollectionProvider.find("asana")).contains(CollectionProvider.ASANA);
        assertThat(CollectionProvider.find("clickup")).contains(CollectionProvider.CLICKUP);
    }

    // PipelineService는 CollectionProvider 선언 순서(EnumMap)로 순차 수집하고, 한 provider가 실패하면
    // 이후 provider는 돌지 않는다. 신규 소스는 항상 마지막에 선언해 기존 5소스(github/jira/slack/linear/asana)의
    // 수집 순서·장애 격리를 건드리지 않는다.
    @Test
    void values_declaresClickupLastToProtectExistingProviders() {
        assertThat(CollectionProvider.values()).containsExactly(
                CollectionProvider.GITHUB,
                CollectionProvider.JIRA,
                CollectionProvider.SLACK,
                CollectionProvider.LINEAR,
                CollectionProvider.ASANA,
                CollectionProvider.CLICKUP
        );
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
        assertThat(CollectionProvider.fromPath("asana")).isEqualTo(CollectionProvider.ASANA);
        assertThat(CollectionProvider.fromPath("clickup")).isEqualTo(CollectionProvider.CLICKUP);
    }

    // URL 경로 변수용 — 미지원이면 예외 (컨트롤러가 400으로 변환)
    @Test
    void fromPath_throwsForUnsupportedValue() {
        assertThatThrownBy(() -> CollectionProvider.fromPath("GITHUB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported collection provider");
    }
}
