package com.history.pipeline_worker.source.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublishException;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * NotionCollector: ClickUpCollectorTest 컨벤션(MockitoExtension + 실 NotionNormalizer)을 미러하되,
 * checkpoint 규약이 다르다 — search가 last_edited_time 내림차순이라 페이지 단위 전진이 아니라
 * "그 실행에서 본 전체 최대 occurredAt으로 끝에 한 번" 전진해야 한다(§5-2). 저장된 커서가 없으면
 * null을 그대로 넘긴다(Instant.EPOCH가 아니다) — NotionRawService는 null을 "컷오프 없음"으로 읽는다.
 */
@ExtendWith(MockitoExtension.class)
class NotionCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final byte[] NOTION_TOKEN = new byte[] {7};

    @Mock
    private NotionRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private NotionCollector collector;

    @BeforeEach
    void setUp() {
        collector = new NotionCollector(
                rawService,
                new NotionNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService,
                new ObjectMapper()
        );
    }

    // ─── collect ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("저장된 checkpoint가 없으면 null을 그대로 전달한다(EPOCH 아님) — NotionRawService가 null을 컷오프 없음으로 읽는다")
    void collect_noStoredCursor_passesNullNotEpoch() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", null);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.searchPages(context, null))
                .thenReturn(new NotionRawService.NotionSearchPageResult(List.of(), null));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, null);
    }

    @Test
    @DisplayName("여러 search 배치를 발행한 뒤 전체 배치의 최대 occurredAt(last_edited_time)으로 커서를 한 번만 갱신한다")
    void collect_multipleSearchBatches_advancesCursorOnceWithBatchMaxOccurredAt() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", null);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.fetchAllUsers("Bearer token")).thenReturn(Map.of());

        Map<String, Object> page1 = notionPage("page-1", "2026-08-10T00:00:00.000Z");
        Map<String, Object> page2 = notionPage("page-2", "2026-08-05T00:00:00.000Z");
        when(rawService.searchPages(context, null))
                .thenReturn(new NotionRawService.NotionSearchPageResult(List.of(page1), "cursor-2"));
        when(rawService.searchPages(context, "cursor-2"))
                .thenReturn(new NotionRawService.NotionSearchPageResult(List.of(page2), null));
        when(rawService.fetchPageBody(context, "page-1")).thenReturn("본문1");
        when(rawService.fetchPageBody(context, "page-2")).thenReturn("본문2");
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        // 내림차순 응답이라 첫 배치가 더 최신이지만, 커서는 "그 실행에서 본 전체 최대"로 한 번만
        // 갱신된다 — 순서에 의존하지 않는 CursorProgress.later 누적이다.
        verify(checkpointService, times(1)).updateCursor(PROJECT_ID, CollectionProvider.NOTION,
                NotionCollector.PAGES_CURSOR, Instant.parse("2026-08-10T00:00:00.000Z"));
    }

    @Test
    @DisplayName("사용자 맵은 수집 실행마다 한 번만 조회한다 — 배치·페이지 수와 무관")
    void collect_fetchesUsersOnceRegardlessOfPageCount() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", null);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.fetchAllUsers("Bearer token")).thenReturn(Map.of());
        when(rawService.searchPages(context, null)).thenReturn(
                new NotionRawService.NotionSearchPageResult(List.of(notionPage("page-1", "2026-08-10T00:00:00.000Z")), "c2"));
        when(rawService.searchPages(context, "c2")).thenReturn(
                new NotionRawService.NotionSearchPageResult(List.of(notionPage("page-2", "2026-08-09T00:00:00.000Z")), null));
        when(rawService.fetchPageBody(any(), anyString())).thenReturn("");
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        collector.collect(PROJECT_ID, request);

        verify(rawService, times(1)).fetchAllUsers("Bearer token");
    }

    @Test
    @DisplayName("수집할 변경이 0건이면 사용자 맵을 아예 조회하지 않는다 — 필요 없는 실행에서 구성원 개인정보를 가져오지 않는다")
    void collect_noChangedPages_doesNotFetchUsers() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", null);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.searchPages(context, null))
                .thenReturn(new NotionRawService.NotionSearchPageResult(List.of(), null));

        collector.collect(PROJECT_ID, request);

        verify(rawService, never()).fetchAllUsers(anyString());
    }

    @Test
    @DisplayName("id 없는 page는 본문 조회·발행 대상에서 건너뛴다")
    void collect_pageWithoutId_isSkipped() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", null);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.searchPages(context, null)).thenReturn(
                new NotionRawService.NotionSearchPageResult(List.of(Map.of("object", "page")), null));
        when(eventPublisher.publishAll(anyList())).thenReturn(0);

        collector.collect(PROJECT_ID, request);

        verify(rawService, never()).fetchPageBody(any(), anyString());
    }

    @Test
    @DisplayName("발행 실패(EventPublishException) 시 예외를 전파하고 커서를 전진시키지 않는다")
    void collect_publishFailure_throwsAndDoesNotAdvanceCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", null);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.fetchAllUsers("Bearer token")).thenReturn(Map.of());
        when(rawService.searchPages(context, null)).thenReturn(
                new NotionRawService.NotionSearchPageResult(List.of(notionPage("page-1", "2026-08-10T00:00:00.000Z")), null));
        when(rawService.fetchPageBody(context, "page-1")).thenReturn("본문");
        when(eventPublisher.publishAll(anyList())).thenThrow(new EventPublishException("publish failed"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("publish failed");
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    void collect_storedCursor_passesStoredCursorAsCheckpoint() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        Instant storedCursor = Instant.parse("2026-08-01T00:00:00Z");
        NotionRawService.NotionFetchContext context = new NotionRawService.NotionFetchContext("Bearer token", storedCursor);
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.NOTION))
                .thenReturn(Map.of(NotionCollector.PAGES_CURSOR, storedCursor));
        when(rawService.prepareFetchContext(request, storedCursor)).thenReturn(context);
        when(rawService.searchPages(context, null))
                .thenReturn(new NotionRawService.NotionSearchPageResult(List.of(), null));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, storedCursor);
    }

    // ─── resolveFetchRequest ────────────────────────────────────────────────

    @Test
    @DisplayName("access_token만 Bearer로 감싸 반환한다 — 선택 단계가 없어 external_ref는 읽지 않는다(projectKey=null)")
    void resolveFetchRequest_buildsBearerRequestWithoutReadingExternalRef() {
        when(credentialCryptoService.decrypt(NOTION_TOKEN)).thenReturn(credentialJson("notion-access-token"));

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                notionRow(Map.of("workspace_id", "W1", "workspace_name", "Acme"), NOTION_TOKEN));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer notion-access-token");
            assertThat(request.projectKey()).isNull();
            assertThat(request.options()).isEmpty();
        });
    }

    @Test
    void resolveFetchRequest_missingEncryptedCredential_throwsConfigurationError() {
        assertThatThrownBy(() -> collector.resolveFetchRequest(notionRow(Map.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing encrypted credential for provider: notion");
    }

    @Test
    void resolveFetchRequest_brokenCredentialJson_throwsIllegalStateException() {
        when(credentialCryptoService.decrypt(NOTION_TOKEN)).thenReturn("not-valid-json");

        assertThatThrownBy(() -> collector.resolveFetchRequest(notionRow(Map.of(), NOTION_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse Notion credential JSON.");
    }

    @Test
    void resolveFetchRequest_missingAccessTokenField_throwsConfigurationError() {
        when(credentialCryptoService.decrypt(NOTION_TOKEN)).thenReturn("{\"refresh_token\":\"r\"}");

        assertThatThrownBy(() -> collector.resolveFetchRequest(notionRow(Map.of(), NOTION_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Notion credential field: access_token");
    }

    // ─── 헬퍼 ───────────────────────────────────────────────────────────────

    private ProjectIntegrationRepository.IntegrationRow notionRow(Map<String, Object> externalRef, byte[] credential) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "notion", externalRef, credential, null, null);
    }

    private String credentialJson(String accessToken) {
        return "{\"access_token\":\"" + accessToken + "\",\"refresh_token\":\"r\"}";
    }

    private Map<String, Object> notionPage(String id, String lastEditedTime) {
        return Map.of(
                "id", id,
                "object", "page",
                "last_edited_time", lastEditedTime,
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", Map.of()
        );
    }
}
