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

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
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

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "1201444444444444");
    }

    @Test
    @DisplayName("Asana V1 URL(/1/{workspace}/task/{task}, project 세그먼트 없음) → task gid 추출")
    void extract_asanaV1UrlWithoutProjectSegment_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/1/98765/task/1201555555555555");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "1201555555555555");
    }

    @Test
    @DisplayName("V0 URL 뒤에 /f 접미가 붙어도(.../0/123/456/f) task gid 추출")
    void extract_asanaV0UrlWithTrailingSlashF_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/0/123/456/f");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @Test
    @DisplayName("쿼리스트링이 붙은 Asana URL도 gid 정상 추출")
    void extract_asanaUrlWithQueryString_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.asana.com/0/123/456?focus=true");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    @Test
    @DisplayName("Slack 링크 래핑(<url|텍스트>) 안의 Asana URL도 gid 추출")
    void extract_asanaUrlWrappedInSlackLink_taskGidExtracted() {
        Map<String, Object> refs = extractor.extract("<https://app.asana.com/0/123/456|태스크 이름>");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
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

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
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
        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "456");
    }

    // ─── ClickUp 태스크 URL의 task id (묶음 1) ──────────────────────────────────
    // ClickUp도 이슈 키(ABC-123) 체계가 없어 공유 태스크 URL에서 task id를 추출한다.
    // 공유 URL: https://app.clickup.com/t/{task_id} — task id는 소문자 영숫자 (예: 868czp8t3)
    // Custom Task ID(유료) URL: https://app.clickup.com/t/{team_id}/{CUSTOM-123} — team_id는 숫자,
    //   custom key는 대문자·숫자·언더스코어 접두 + "-" + 숫자 (예: ABC-123). 이 형식에서 숫자 team_id를
    //   task id로 오인하면 안 되므로 배타 처리 대상이며, 대신 기존 ISSUE_KEY 패턴이 CUSTOM-123을 잡는다
    //   (linear.app URL 경로의 identifier를 잡는 것과 같은 전례).
    // 리스트 뷰 등 비태스크 URL(.../{team_id}/v/li/{list_id})은 태스크 참조가 아니다.
    // refs.get("issueExternalRefs") = List<Map<String,Object>> [{source: "CLICKUP", externalId: "<task id>"}].

    @Test
    @DisplayName("ClickUp 공유 태스크 URL(/t/{task_id}) → task id 추출")
    void extract_clickupTaskUrl_taskIdExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.clickup.com/t/868czp8t3");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactly(
                Map.of("source", "CLICKUP", "externalId", "868czp8t3")
        );
    }

    @Test
    @DisplayName("쿼리스트링이 붙은 ClickUp URL도 task id 정상 추출")
    void extract_clickupUrlWithQueryString_taskIdExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.clickup.com/t/868czp8t3?comment=456");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactly(
                Map.of("source", "CLICKUP", "externalId", "868czp8t3")
        );
    }

    @Test
    @DisplayName("Slack 링크 래핑(<url|텍스트>) 안의 ClickUp URL도 task id 추출")
    void extract_clickupUrlWrappedInSlackLink_taskIdExtracted() {
        Map<String, Object> refs = extractor.extract("<https://app.clickup.com/t/868czp8t3|태스크 보기>");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactly(
                Map.of("source", "CLICKUP", "externalId", "868czp8t3")
        );
    }

    @Test
    @DisplayName("Custom Task ID URL(/t/{team_id}/{CUSTOM-123})은 숫자 team_id를 task id로 오인하지 않는다 "
            + "— issueExternalRefs에 CLICKUP 항목이 생기지 않고, 대신 기존 issueKey 패턴이 URL 안의 "
            + "CUSTOM-123을 잡는다 (linear.app URL 전례와 동일)")
    void extract_clickupCustomTaskIdUrl_teamIdNotMisidentifiedAsTaskId() {
        Map<String, Object> refs = extractor.extract("https://app.clickup.com/t/1234567/ABC-123");

        assertThat(refs).doesNotContainKey("issueExternalRefs");
        assertThat(refs).containsEntry("issueKey", "ABC-123");
        assertThat(refs).containsEntry("issueKeys", List.of("ABC-123"));
    }

    @Test
    @DisplayName("세그먼트 1개짜리 전체 숫자 ClickUp URL(/t/1234567)은 legacy 숫자 task id로 간주해 추출한다 "
            + "— 배타 처리는 team_id+CUSTOM-key 두 세그먼트 형식에만 적용된다")
    void extract_clickupSingleNumericSegmentUrl_taskIdExtracted() {
        Map<String, Object> refs = extractor.extract("https://app.clickup.com/t/1234567");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactly(
                Map.of("source", "CLICKUP", "externalId", "1234567")
        );
    }

    @Test
    @DisplayName("같은 ClickUp 태스크 URL이 2회 등장해도 issueExternalRefs 원소는 1개 (중복 제거)")
    void extract_duplicateClickupUrl_deduplicated() {
        Map<String, Object> refs = extractor.extract(
                "https://app.clickup.com/t/868czp8t3 mentioned again at https://app.clickup.com/t/868czp8t3");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactly(
                Map.of("source", "CLICKUP", "externalId", "868czp8t3")
        );
    }

    @Test
    @DisplayName("ClickUp 리스트 뷰 URL(/{team_id}/v/li/{list_id})은 태스크 참조가 아니므로 매치하지 않음")
    void extract_clickupListViewUrl_notMatched() {
        Map<String, Object> refs = extractor.extract("https://app.clickup.com/1234567/v/li/90112345");

        assertThat(refs).doesNotContainKey("issueExternalRefs");
    }

    @Test
    @DisplayName("Asana URL·ClickUp URL·Jira 키가 한 텍스트에 공존하면 셋 다 정확히 추출 (오추출·누락 없음)")
    void extract_asanaUrlClickupUrlAndIssueKey_allExtractedExactly() {
        Map<String, Object> refs = extractor.extract(
                "HT-7 relates to https://app.asana.com/0/123/456 and https://app.clickup.com/t/868czp8t3");

        assertThat(refs).containsEntry("issueKey", "HT-7");
        assertThat(refs).containsEntry("issueKeys", List.of("HT-7"));
        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactlyInAnyOrder(
                Map.of("source", "ASANA", "externalId", "456"),
                Map.of("source", "CLICKUP", "externalId", "868czp8t3")
        );
    }

    // ─── Notion 페이지 URL의 page id (documentExternalRefs — issueExternalRefs와 분리된 키) ──
    // Notion은 이슈 키 체계가 없는 대신 문서(Document) 아키타입 참조를 담는다. URL에는 하이픈이
    // 없지만 API의 external_id(page id)는 하이픈 있는 UUID라 정규화가 필요하다.
    // refs.get("documentExternalRefs") = List<Map<String,Object>> [{source: "NOTION", externalId: "<uuid>"}].

    @Test
    @DisplayName("제목 슬러그가 붙은 Notion URL → 32자리 hex를 하이픈 UUID로 정규화해 추출")
    void extract_notionUrlWithTitleSlug_hexNormalizedToHyphenatedUuid() {
        Map<String, Object> refs = extractor.extract(
                "See https://www.notion.so/Auth-Design-1234567890abcdef1234567890abcdef for context");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
        // issueExternalRefs 키는 건드리지 않는다 — 문서 참조와 이슈 참조는 분리된 출력이다.
        assertThat(refs).doesNotContainKey("issueExternalRefs");
    }

    @Test
    @DisplayName("워크스페이스 세그먼트 없이 32자리 hex만 있는 Notion URL도 추출")
    void extract_notionUrlWithoutTitleSlug_extracted() {
        Map<String, Object> refs = extractor.extract(
                "https://www.notion.so/1234567890abcdef1234567890abcdef");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("www. 없는 notion.so 호스트도 추출")
    void extract_notionUrlWithoutWww_extracted() {
        Map<String, Object> refs = extractor.extract(
                "https://notion.so/1234567890abcdef1234567890abcdef");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("쿼리스트링이 붙은 Notion URL도 page id 정상 추출")
    void extract_notionUrlWithQueryString_extracted() {
        Map<String, Object> refs = extractor.extract(
                "https://www.notion.so/Auth-Design-1234567890abcdef1234567890abcdef?pvs=4");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("워크스페이스 세그먼트가 붙은 Notion URL(/{workspace}/{slug}-{hex})도 추출")
    void extract_notionUrlWithWorkspaceSegment_extracted() {
        Map<String, Object> refs = extractor.extract(
                "https://www.notion.so/acme/Auth-Design-1234567890abcdef1234567890abcdef");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("대문자 hex로 붙은 Notion URL도 대소문자 무관 매치 후 소문자 UUID로 정규화")
    void extract_notionUrlWithUppercaseHex_normalizedToLowercase() {
        Map<String, Object> refs = extractor.extract(
                "https://www.notion.so/1234567890ABCDEF1234567890ABCDEF");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("같은 Notion URL이 2회 등장해도 documentExternalRefs 원소는 1개 (중복 제거)")
    void extract_duplicateNotionUrl_deduplicated() {
        Map<String, Object> refs = extractor.extract(
                "https://www.notion.so/1234567890abcdef1234567890abcdef mentioned again at "
                        + "https://www.notion.so/1234567890abcdef1234567890abcdef");

        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("Notion URL이 없는 텍스트 → documentExternalRefs 키 자체 부재")
    void extract_noNotionUrl_noDocumentExternalRefsKey() {
        Map<String, Object> refs = extractor.extract("just a regular commit message");

        assertThat(refs).doesNotContainKey("documentExternalRefs");
    }

    @Test
    @DisplayName("Jira 이슈 키와 Notion URL이 한 텍스트에 공존하면 issueKey·documentExternalRefs 둘 다 산출")
    void extract_issueKeyAndNotionUrl_bothExtracted() {
        Map<String, Object> refs = extractor.extract(
                "HT-7 관련 설계 문서: https://www.notion.so/Auth-Design-1234567890abcdef1234567890abcdef");

        assertThat(refs).containsEntry("issueKey", "HT-7");
        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @Test
    @DisplayName("Asana URL과 Notion URL이 공존하면 issueExternalRefs·documentExternalRefs로 갈라 산출된다")
    void extract_asanaUrlAndNotionUrl_splitIntoDifferentRefsKeys() {
        Map<String, Object> refs = extractor.extract(
                "https://app.asana.com/0/123/456 and https://www.notion.so/1234567890abcdef1234567890abcdef");

        List<Map<String, Object>> issueExternalRefs = externalRefs(refs);
        assertThat(issueExternalRefs).containsExactly(Map.of("source", "ASANA", "externalId", "456"));
        List<Map<String, Object>> documentExternalRefs = documentExternalRefs(refs);
        assertThat(documentExternalRefs).containsExactly(
                Map.of("source", "NOTION", "externalId", "12345678-90ab-cdef-1234-567890abcdef")
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> externalRefs(Map<String, Object> refs) {
        return (List<Map<String, Object>>) refs.get("issueExternalRefs");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> documentExternalRefs(Map<String, Object> refs) {
        return (List<Map<String, Object>>) refs.get("documentExternalRefs");
    }
}
