package com.history.pipeline_worker.normalizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefsExtractor: 텍스트에서 Jira 키·PR 번호를 정규식으로 추출하는 순수 컴포넌트.
 * 외부 의존성 없음 → Spring 컨텍스트 없이 단순 인스턴스화로 테스트.
 *
 * 반환 타입은 Map&lt;String, Object&gt; — issueKey/prNumber는 String, issueKeys는 List&lt;String&gt;.
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
    @DisplayName("일반 Jira 키(PROJECT-123) 추출 — issueKey + issueKeys 모두 포함")
    void extract_standardJiraKey() {
        Map<String, Object> refs = extractor.extract("Fix for PROJECT-123 resolved");
        assertThat(refs).containsEntry("issueKey", "PROJECT-123");
        assertThat(refs).containsEntry("issueKeys", List.of("PROJECT-123"));
    }

    @Test
    @DisplayName("두 글자 짧은 Jira 키(AB-1) 추출")
    void extract_shortJiraKey() {
        Map<String, Object> refs = extractor.extract("Relates to AB-1");
        assertThat(refs).containsEntry("issueKey", "AB-1");
        assertThat(refs).containsEntry("issueKeys", List.of("AB-1"));
    }

    @Test
    @DisplayName("소문자 Jira 키는 패턴 불일치 → issueKey 없음")
    void extract_lowercaseJiraKey_notMatched() {
        // 패턴이 [A-Z]{2,} 대문자만 허용하므로 소문자 키는 추출되지 않아야 함
        Map<String, Object> refs = extractor.extract("project-123 fix");
        assertThat(refs).doesNotContainKey("issueKey");
        assertThat(refs).doesNotContainKey("issueKeys");
    }

    @Test
    @DisplayName("여러 Jira 키 → issueKey는 첫 번째, issueKeys는 전체 (입력 순서 유지)")
    void extract_multipleJiraKeys() {
        Map<String, Object> refs = extractor.extract("ALPHA-1 and BETA-2 referenced");
        assertThat(refs).containsEntry("issueKey", "ALPHA-1");
        assertThat(refs).containsEntry("issueKeys", List.of("ALPHA-1", "BETA-2"));
    }

    @Test
    @DisplayName("같은 Jira 키가 중복 등장하면 issueKeys에서 중복 제거")
    void extract_duplicateJiraKeys_deduplicatedInList() {
        Map<String, Object> refs = extractor.extract("HT-5 in title, HT-5 again in body, HT-12 too");
        assertThat(refs).containsEntry("issueKey", "HT-5");
        assertThat(refs).containsEntry("issueKeys", List.of("HT-5", "HT-12"));
    }

    // ─── PR 번호 추출 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("'PR #42' 형식 PR 번호 추출")
    void extract_prNumberWithSpace() {
        Map<String, Object> refs = extractor.extract("Closes PR #42");
        assertThat(refs).containsEntry("prNumber", "42");
    }

    @Test
    @DisplayName("'PR#42' 공백 없는 형식도 추출")
    void extract_prNumberWithoutSpace() {
        Map<String, Object> refs = extractor.extract("See PR#42");
        assertThat(refs).containsEntry("prNumber", "42");
    }

    @Test
    @DisplayName("소문자 'pr #42' 대소문자 무관 추출")
    void extract_prNumberLowercase() {
        Map<String, Object> refs = extractor.extract("pr #42 merged");
        assertThat(refs).containsEntry("prNumber", "42");
    }

    // ─── 복합 케이스 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Jira 키와 PR 번호가 함께 있으면 둘 다 추출")
    void extract_issueKeyAndPrNumber_bothExtracted() {
        Map<String, Object> refs = extractor.extract("Implements PROJ-99, closes PR #7");
        assertThat(refs)
                .containsEntry("issueKey", "PROJ-99")
                .containsEntry("issueKeys", List.of("PROJ-99"))
                .containsEntry("prNumber", "7");
    }

    @Test
    @DisplayName("참조 없는 일반 텍스트 → 빈 map 반환")
    void extract_plainText_noRefs() {
        assertThat(extractor.extract("just a regular commit message")).isEmpty();
    }

    // ─── Linear 이슈 URL 경로의 identifier (갭 4) ──────────────────────────────────
    // linear.app 이슈 URL은 https://linear.app/{workspace}/issue/{identifier}/{slug} 형태다.
    // identifier(ENG-42)의 앞뒤는 항상 "/"(비단어 문자)라서 기존 \b([A-Z]{2,}-\d+)\b 패턴의
    // 단어 경계 조건이 그대로 성립한다 — 별도의 URL 전용 패턴 없이도 이미 추출된다.
    // 이 테스트는 프로덕션 변경 없이 그 사실을 고정한다(red 아님).

    @Test
    @DisplayName("linear.app 이슈 URL 경로 안의 identifier(ENG-42)도 기존 이슈 키 패턴이 이미 추출한다 "
            + "— 슬래시가 단어 경계 역할을 하므로 linear.app 전용 패턴을 추가할 필요가 없다")
    void extract_linearIssueUrlIdentifier_alreadyMatchedByExistingIssueKeyPattern() {
        Map<String, Object> refs = extractor.extract(
                "See https://linear.app/acme/issue/ENG-42/fix-login-bug for context");

        assertThat(refs).containsEntry("issueKey", "ENG-42");
        assertThat(refs).containsEntry("issueKeys", List.of("ENG-42"));
    }

    // ─── Asana 태스크 URL의 gid (묶음 3) ────────────────────────────────────────
    // Asana는 이슈 키(ABC-123)가 없어 태스크 URL에서 gid를 추출한다.
    // V0: https://app.asana.com/0/{project_gid}/{task_gid} — 마지막 숫자 세그먼트가 task gid
    // V1: https://app.asana.com/1/{workspace_gid}/task/{task_gid}
    //     https://app.asana.com/1/{workspace_gid}/project/{project_gid}/task/{task_gid} — "/task/" 다음 숫자가 task gid
    // refs.get("issueExternalRefs") = List<Map<String,Object>> [{source: "ASANA", externalId: "<gid>"}], 매치 없으면 키 자체 부재.

    @Test
    @DisplayName("Asana V0 URL(/0/{project}/{task}) → 두 번째 숫자 세그먼트(task gid)를 추출 (project gid 아님)")
    void extract_asanaV0Url_secondSegmentIsTaskGid() {
        Map<String, Object> refs = extractor.extract(
                "See https://app.asana.com/0/1201111111111111/1201222222222222 for details");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "1201222222222222");
    }

    @Test
    @DisplayName("Asana V1 URL(/1/{workspace}/project/{project}/task/{task}) → task gid 추출")
    void extract_asanaV1UrlWithProjectSegment_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract(
                "https://app.asana.com/1/98765/project/1201333333333333/task/1201444444444444");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "1201444444444444");
    }

    @Test
    @DisplayName("Asana V1 URL(/1/{workspace}/task/{task}, project 세그먼트 없음) → task gid 추출")
    void extract_asanaV1UrlWithoutProjectSegment_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/1/98765/task/1201555555555555");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "1201555555555555");
    }

    @Test
    @DisplayName("V0 URL 뒤에 /f 접미가 붙어도(.../0/123/456/f) task gid 추출")
    void extract_asanaV0UrlWithTrailingSlashF_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/0/123/456/f");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @Test
    @DisplayName("쿼리스트링이 붙은 Asana URL도 gid 정상 추출")
    void extract_asanaUrlWithQueryString_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/0/123/456?focus=true");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @Test
    @DisplayName("Slack 링크 래핑(<url|텍스트>) 안의 Asana URL도 gid 추출")
    void extract_asanaUrlWrappedInSlackLink_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("<https://app.asana.com/0/123/456|태스크 이름>");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @Test
    @DisplayName("같은 Asana URL이 2회 등장해도 issueExternalRefs 원소는 1개 (중복 제거)")
    void extract_duplicateAsanaUrl_deduplicated() {
        Map<String, Object> refs = extractor.extract(
                "https://app.asana.com/0/123/456 mentioned again at https://app.asana.com/0/123/456");

        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @Test
    @DisplayName("프로젝트만 있는 V0 URL(세그먼트 1개, .../0/123)은 태스크가 아니므로 매치하지 않음")
    void extract_asanaV0ProjectOnlyUrl_notMatched() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/0/123 project overview");

        assertThat(refs).doesNotContainKey("issueExternalRefs");
    }

    @Test
    @DisplayName("task 세그먼트가 없는 V1 URL(.../1/{workspace}/project/{project})은 매치하지 않음")
    void extract_asanaV1WithoutTaskSegment_notMatched() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/1/777/project/123 project overview");

        assertThat(refs).doesNotContainKey("issueExternalRefs");
    }

    @Test
    @DisplayName("Asana URL이 없는 텍스트 → issueExternalRefs 키 자체 부재")
    void extract_noAsanaUrl_noIssueExternalRefsKey() {
        Map<String, Object> refs = extractor.extract("just a regular commit message");

        assertThat(refs).doesNotContainKey("issueExternalRefs");
    }

    @Test
    @DisplayName("Jira 이슈 키와 Asana URL이 한 텍스트에 공존하면 issueKey·issueExternalRefs 둘 다 산출")
    void extract_issueKeyAndAsanaUrl_bothExtracted() {
        Map<String, Object> refs = extractor.extract("HT-7 relates to https://app.asana.com/0/123/456");

        assertThat(refs).containsEntry("issueKey", "HT-7");
        List<Map<String, Object>> issueExternalRefs = asanaRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asanaRefs(Map<String, Object> refs) {
        return (List<Map<String, Object>>) refs.get("issueExternalRefs");
    }
}
