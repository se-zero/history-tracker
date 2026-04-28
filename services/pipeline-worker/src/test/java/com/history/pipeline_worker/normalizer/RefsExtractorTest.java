package com.history.pipeline_worker.normalizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefsExtractor: 텍스트에서 Jira 키·PR 번호를 정규식으로 추출하는 순수 컴포넌트.
 * 외부 의존성 없음 → Spring 컨텍스트 없이 단순 인스턴스화로 테스트.
 */
class RefsExtractorTest {

    private RefsExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new RefsExtractor();
    }

    // ─── null / 빈 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 입력 → 빈 map 반환")
    void extract_null_returnsEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("빈 문자열 입력 → 빈 map 반환")
    void extract_emptyString_returnsEmpty() {
        assertThat(extractor.extract("")).isEmpty();
    }

    @Test
    @DisplayName("공백만 있는 입력 → 빈 map 반환")
    void extract_blankString_returnsEmpty() {
        assertThat(extractor.extract("   ")).isEmpty();
    }

    // ─── Jira 키 추출 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("일반 Jira 키(PROJECT-123) 추출")
    void extract_standardJiraKey() {
        Map<String, String> refs = extractor.extract("Fix for PROJECT-123 resolved");
        assertThat(refs).containsEntry("jiraKey", "PROJECT-123");
    }

    @Test
    @DisplayName("두 글자 짧은 Jira 키(AB-1) 추출")
    void extract_shortJiraKey() {
        Map<String, String> refs = extractor.extract("Relates to AB-1");
        assertThat(refs).containsEntry("jiraKey", "AB-1");
    }

    @Test
    @DisplayName("소문자 Jira 키는 패턴 불일치 → jiraKey 없음")
    void extract_lowercaseJiraKey_notMatched() {
        // 패턴이 [A-Z]{2,} 대문자만 허용하므로 소문자 키는 추출되지 않아야 함
        Map<String, String> refs = extractor.extract("project-123 fix");
        assertThat(refs).doesNotContainKey("jiraKey");
    }

    @Test
    @DisplayName("여러 Jira 키 중 첫 번째만 반환 (대표 참조)")
    void extract_multipleJiraKeys_returnsFirst() {
        Map<String, String> refs = extractor.extract("ALPHA-1 and BETA-2 referenced");
        assertThat(refs).containsEntry("jiraKey", "ALPHA-1");
    }

    // ─── PR 번호 추출 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("'PR #42' 형식 PR 번호 추출")
    void extract_prNumberWithSpace() {
        Map<String, String> refs = extractor.extract("Closes PR #42");
        assertThat(refs).containsEntry("prNumber", "42");
    }

    @Test
    @DisplayName("'PR#42' 공백 없는 형식도 추출")
    void extract_prNumberWithoutSpace() {
        Map<String, String> refs = extractor.extract("See PR#42");
        assertThat(refs).containsEntry("prNumber", "42");
    }

    @Test
    @DisplayName("소문자 'pr #42' 대소문자 무관 추출")
    void extract_prNumberLowercase() {
        Map<String, String> refs = extractor.extract("pr #42 merged");
        assertThat(refs).containsEntry("prNumber", "42");
    }

    // ─── 복합 케이스 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Jira 키와 PR 번호가 함께 있으면 둘 다 추출")
    void extract_jiraKeyAndPrNumber_bothExtracted() {
        Map<String, String> refs = extractor.extract("Implements PROJ-99, closes PR #7");
        assertThat(refs)
                .containsEntry("jiraKey", "PROJ-99")
                .containsEntry("prNumber", "7");
    }

    @Test
    @DisplayName("참조 없는 일반 텍스트 → 빈 map 반환")
    void extract_plainText_noRefs() {
        assertThat(extractor.extract("just a regular commit message")).isEmpty();
    }
}
