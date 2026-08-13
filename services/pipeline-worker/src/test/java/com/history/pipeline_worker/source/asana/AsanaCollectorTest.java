package com.history.pipeline_worker.source.asana;

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
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
 * AsanaCollector: LinearCollectorTest 컨벤션(MockitoExtension + 실 AsanaNormalizer)을 미러한다.
 *
 * <p>checkpoint는 Jira가 아니라 Slack/Linear 패턴을 따른다 — Asana API는 조회 결과의 정렬 순서를
 * 보장하지 않으므로, 페이지 단위로 커서를 전진시키면 아직 못 본 태스크가 다음 수집에서 영구히
 * 건너뛰어질 수 있다. 그래서 전체 실행이 끝까지 성공한 경우에만 배치 전체의 최대 occurredAt으로
 * 커서를 한 번 전진시킨다.</p>
 */
@ExtendWith(MockitoExtension.class)
class AsanaCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final String PROJECT_GID = "1200000000000001";
    private static final byte[] ASANA_TOKEN = new byte[] {6};
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AsanaRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private AsanaCollector collector;

    @BeforeEach
    void setUp() {
        collector = new AsanaCollector(
                rawService,
                new AsanaNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService,
                new ObjectMapper(),
                CLOCK
        );
    }

    // ─── collect: checkpoint (Slack/Linear 패턴 — 전체 실행 성공 후 배치 최대 occurredAt으로 1회 전진) ─

    @Test
    @DisplayName("여러 페이지를 발행한 뒤 전체 배치의 최대 occurredAt으로 커서를 한 번만 갱신한다 — " +
            "Asana는 정렬 보장이 없으므로 뒤 페이지가 더 최신이어도 배치 전체에서 정확한 최댓값을 계산해야 한다")
    void collect_multiplePages_advancesCursorOnceWithBatchMaxOccurredAtRegardlessOfPageOrder() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1)).thenReturn(new AsanaRawService.AsanaTaskPage(
                buildTaskPageBody("task-1", "2024-05-01T00:00:00.000Z"), true, "offset-2", false));
        when(rawService.fetchTaskPage(context, "offset-2", 2)).thenReturn(new AsanaRawService.AsanaTaskPage(
                buildTaskPageBody("task-2", "2024-05-10T00:00:00.000Z"), false, null, false));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointService, times(1)).updateCursor(PROJECT_ID, CollectionProvider.ASANA,
                AsanaCollector.UPDATED_CURSOR, Instant.parse("2024-05-10T00:00:00Z"));
    }

    @Test
    @DisplayName("max pages 도달(첫 페이지) 시 발행하지 않고 정상 종료한다(예외 아님), 커서도 전진하지 않는다")
    void collect_limitReachedOnFirstPage_stopsWithoutPublishingOrAdvancingCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1))
                .thenReturn(new AsanaRawService.AsanaTaskPage(Map.of("data", List.of()), false, null, true));

        assertThat(collector.collect(PROJECT_ID, request)).isZero();

        verifyNoInteractions(eventPublisher);
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("이전 페이지는 이미 발행했는데 다음 페이지에서 max pages에 걸리면, 그 실행 전체의 커서는 " +
            "전진시키지 않는다 — 부분 전진은 상한 아래의 미처리(아직 못 본) 태스크를 다음 수집에서 영구히 건너뛰게 한다")
    void collect_limitReachedAfterEarlierPagePublished_stillDoesNotAdvanceCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1)).thenReturn(new AsanaRawService.AsanaTaskPage(
                buildTaskPageBody("task-1", "2024-05-10T00:00:00.000Z"), true, "offset-2", false));
        when(rawService.fetchTaskPage(context, "offset-2", 2))
                .thenReturn(new AsanaRawService.AsanaTaskPage(Map.of("data", List.of()), false, null, true));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(1);
        verify(eventPublisher, times(1)).publishAll(anyList());
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("발행 실패(EventPublishException) 시 예외를 전파하고 커서를 전진시키지 않는다")
    void collect_publishFailure_throwsAndDoesNotAdvanceCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1)).thenReturn(new AsanaRawService.AsanaTaskPage(
                buildTaskPageBody("task-1", "2024-05-10T00:00:00.000Z"), false, null, false));
        when(eventPublisher.publishAll(anyList())).thenThrow(new EventPublishException("publish failed"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("publish failed");
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    // LinearCollector 소스 확인 결과: 루프가 limitReached 없이 끝까지 성공하면(수집 0건이어도)
    // checkpointService.updateCursor 호출 자체는 생략되지 않고, cursor 값이 null인 채로 호출된다.
    // CheckpointService.updateCursor는 cursorValue==null이면 DB를 건드리지 않는 no-op이라 결과적으로
    // 기존 커서가 유지된다 — 호출 자체가 스킵되는 것이 아니다. Asana도 이 동작을 그대로 미러한다.
    @Test
    @DisplayName("수집 결과가 0건이면 updateCursor를 null 값으로 호출한다 — CheckpointService가 null을 no-op으로 " +
            "처리해 기존 커서를 유지한다")
    void collect_zeroEvents_callsUpdateCursorWithNullPreservingExistingCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1))
                .thenReturn(new AsanaRawService.AsanaTaskPage(Map.of("data", List.of()), false, null, false));

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isZero();
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.ASANA, AsanaCollector.UPDATED_CURSOR, null);
    }

    @Test
    @DisplayName("저장된 checkpoint가 없으면 since로 null이 아닌 Instant.EPOCH를 전달한다")
    void collect_noStoredCursor_passesEpochAsSinceNotNull() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1))
                .thenReturn(new AsanaRawService.AsanaTaskPage(Map.of("data", List.of()), false, null, false));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, Instant.EPOCH);
    }

    @Test
    void collect_storedCursor_passesStoredCursorAsSince() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", PROJECT_GID, Map.of());
        Instant lastScannedAt = Instant.parse("2024-03-01T00:00:00Z");
        AsanaRawService.AsanaFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.ASANA))
                .thenReturn(Map.of(AsanaCollector.UPDATED_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request, lastScannedAt)).thenReturn(context);
        when(rawService.fetchTaskPage(context, null, 1))
                .thenReturn(new AsanaRawService.AsanaTaskPage(Map.of("data", List.of()), false, null, false));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, lastScannedAt);
    }

    // ─── resolveFetchRequest ────────────────────────────────────────────────────

    @Test
    void resolveFetchRequest_buildsBearerRequestWithProjectGid() {
        when(credentialCryptoService.decrypt(ASANA_TOKEN))
                .thenReturn(credentialJson("asana-access-token", "2026-01-01T01:00:00Z"));

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                asanaRow(Map.of("project_gid", PROJECT_GID), ASANA_TOKEN));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer asana-access-token");
            assertThat(request.projectKey()).isEqualTo(PROJECT_GID);
            assertThat(request.options()).isEmpty();
        });
    }

    @Test
    @DisplayName("access_token 만료는 설정 오류가 아니라 지금은 수집 불가다 — 예외가 아니라 empty로 신호한다")
    void resolveFetchRequest_expiredAccessToken_isEmpty() {
        when(credentialCryptoService.decrypt(ASANA_TOKEN))
                .thenReturn(credentialJson("asana-access-token", "2025-12-31T23:59:59Z"));

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                asanaRow(Map.of("project_gid", PROJECT_GID), ASANA_TOKEN));

        assertThat(result).isEmpty();
    }

    @Test
    void resolveFetchRequest_missingEncryptedCredential_throwsConfigurationError() {
        assertThatThrownBy(() -> collector.resolveFetchRequest(asanaRow(Map.of("project_gid", PROJECT_GID), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing encrypted credential for provider: asana");
    }

    @Test
    void resolveFetchRequest_brokenCredentialJson_throwsIllegalStateException() {
        when(credentialCryptoService.decrypt(ASANA_TOKEN)).thenReturn("not-valid-json");

        assertThatThrownBy(() -> collector.resolveFetchRequest(asanaRow(Map.of("project_gid", PROJECT_GID), ASANA_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse Asana credential JSON.");
    }

    @Test
    void resolveFetchRequest_missingProjectGid_throwsConfigurationError() {
        when(credentialCryptoService.decrypt(ASANA_TOKEN))
                .thenReturn(credentialJson("asana-access-token", "2026-01-01T01:00:00Z"));

        assertThatThrownBy(() -> collector.resolveFetchRequest(asanaRow(Map.of(), ASANA_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: project_gid");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private ProjectIntegrationRepository.IntegrationRow asanaRow(Map<String, Object> externalRef, byte[] credential) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "asana", externalRef, credential, null, null);
    }

    private AsanaRawService.AsanaFetchContext fetchContext() {
        return new AsanaRawService.AsanaFetchContext(
                WebClient.builder().build(), "Bearer token", PROJECT_GID, Instant.EPOCH);
    }

    private String credentialJson(String accessToken, String expiresAt) {
        return "{\"access_token\":\"" + accessToken + "\",\"refresh_token\":\"asana-refresh-token\","
                + "\"expires_at\":\"" + expiresAt + "\"}";
    }

    private Map<String, Object> buildTaskPageBody(String gid, String modifiedAt) {
        Map<String, Object> task = new HashMap<>();
        task.put("gid", gid);
        task.put("name", "title-" + gid);
        task.put("notes", "");
        task.put("completed", false);
        task.put("completed_at", null);
        task.put("created_at", modifiedAt);
        task.put("modified_at", modifiedAt);
        task.put("assignee", null);
        task.put("created_by", null);
        task.put("parent", null);

        Map<String, Object> body = new HashMap<>();
        body.put("data", List.of(task));
        return body;
    }
}
