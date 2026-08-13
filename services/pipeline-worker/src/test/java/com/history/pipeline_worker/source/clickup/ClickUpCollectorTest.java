package com.history.pipeline_worker.source.clickup;

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

import java.time.Instant;
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
 * ClickUpCollector: AsanaCollectorTest 컨벤션(MockitoExtension + 실 ClickUpNormalizer)을 미러한다.
 *
 * <p>checkpoint는 Asana와 동일하게 Slack형 패턴을 따른다 — ClickUp /task 응답도 정렬 순서를 보장하지
 * 않으므로, 전체 실행이 끝까지 성공한 경우에만 배치 전체의 최대 occurredAt으로 커서를 한 번
 * 전진시킨다. 다만 페이지 진행은 offset 토큰이 아니라 0부터 시작하는 page 번호다. access_token에
 * 만료 개념이 없어(ClickUp 공식 문서 기준) Asana의 Clock 주입·만료 판정 분기는 없다.</p>
 */
@ExtendWith(MockitoExtension.class)
class ClickUpCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final String WORKSPACE_ID = "4567890";
    private static final String LIST_ID = "123456789";
    private static final byte[] CLICKUP_TOKEN = new byte[] {7};

    @Mock
    private ClickUpRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private ClickUpCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ClickUpCollector(
                rawService,
                new ClickUpNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService,
                new ObjectMapper()
        );
    }

    // ─── collect: checkpoint (Slack/Asana 패턴 — 전체 실행 성공 후 배치 최대 occurredAt으로 1회 전진) ─

    @Test
    @DisplayName("여러 페이지를 발행한 뒤 전체 배치의 최대 occurredAt으로 커서를 한 번만 갱신한다")
    void collect_multiplePages_advancesCursorOnceWithBatchMaxOccurredAt() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", WORKSPACE_ID, Map.of("list_id", LIST_ID));
        ClickUpRawService.ClickUpFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.CLICKUP)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, 0)).thenReturn(new ClickUpRawService.ClickUpTaskPage(
                buildTaskPageBody("task-1", "1700000000000"), true, false));
        when(rawService.fetchTaskPage(context, 1)).thenReturn(new ClickUpRawService.ClickUpTaskPage(
                buildTaskPageBody("task-2", "1700000600000"), false, false));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointService, times(1)).updateCursor(PROJECT_ID, CollectionProvider.CLICKUP,
                ClickUpCollector.UPDATED_CURSOR, Instant.parse("2023-11-14T22:23:20Z"));
    }

    @Test
    @DisplayName("max pages 도달(첫 페이지) 시 발행하지 않고 정상 종료한다(예외 아님), 커서도 전진하지 않는다")
    void collect_limitReachedOnFirstPage_stopsWithoutPublishingOrAdvancingCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", WORKSPACE_ID, Map.of("list_id", LIST_ID));
        ClickUpRawService.ClickUpFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.CLICKUP)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, 0))
                .thenReturn(new ClickUpRawService.ClickUpTaskPage(Map.of("tasks", List.of()), false, true));

        assertThat(collector.collect(PROJECT_ID, request)).isZero();

        verifyNoInteractions(eventPublisher);
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("이전 페이지는 이미 발행했는데 다음 페이지에서 max pages에 걸리면, 그 실행 전체의 커서는 " +
            "전진시키지 않는다 — 부분 전진은 상한 아래의 미처리(아직 못 본) 태스크를 다음 수집에서 영구히 건너뛰게 한다")
    void collect_limitReachedAfterEarlierPagePublished_stillDoesNotAdvanceCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", WORKSPACE_ID, Map.of("list_id", LIST_ID));
        ClickUpRawService.ClickUpFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.CLICKUP)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, 0)).thenReturn(new ClickUpRawService.ClickUpTaskPage(
                buildTaskPageBody("task-1", "1700000600000"), true, false));
        when(rawService.fetchTaskPage(context, 1))
                .thenReturn(new ClickUpRawService.ClickUpTaskPage(Map.of("tasks", List.of()), false, true));
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
        RawFetchRequest request = new RawFetchRequest("Bearer token", WORKSPACE_ID, Map.of("list_id", LIST_ID));
        ClickUpRawService.ClickUpFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.CLICKUP)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, 0)).thenReturn(new ClickUpRawService.ClickUpTaskPage(
                buildTaskPageBody("task-1", "1700000600000"), false, false));
        when(eventPublisher.publishAll(anyList())).thenThrow(new EventPublishException("publish failed"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("publish failed");
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("저장된 checkpoint가 없으면 since로 null이 아닌 Instant.EPOCH를 전달한다")
    void collect_noStoredCursor_passesEpochAsSinceNotNull() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", WORKSPACE_ID, Map.of("list_id", LIST_ID));
        ClickUpRawService.ClickUpFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.CLICKUP)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchTaskPage(context, 0))
                .thenReturn(new ClickUpRawService.ClickUpTaskPage(Map.of("tasks", List.of()), false, false));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, Instant.EPOCH);
    }

    @Test
    void collect_storedCursor_passesStoredCursorAsSince() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", WORKSPACE_ID, Map.of("list_id", LIST_ID));
        Instant lastScannedAt = Instant.parse("2024-03-01T00:00:00Z");
        ClickUpRawService.ClickUpFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.CLICKUP))
                .thenReturn(Map.of(ClickUpCollector.UPDATED_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request, lastScannedAt)).thenReturn(context);
        when(rawService.fetchTaskPage(context, 0))
                .thenReturn(new ClickUpRawService.ClickUpTaskPage(Map.of("tasks", List.of()), false, false));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, lastScannedAt);
    }

    // ─── resolveFetchRequest ────────────────────────────────────────────────────

    @Test
    void resolveFetchRequest_buildsBearerRequestWithWorkspaceIdAndListId() {
        when(credentialCryptoService.decrypt(CLICKUP_TOKEN)).thenReturn(credentialJson("clickup-access-token"));

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                clickUpRow(Map.of("workspace_id", WORKSPACE_ID, "list_id", LIST_ID), CLICKUP_TOKEN));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer clickup-access-token");
            assertThat(request.projectKey()).isEqualTo(WORKSPACE_ID);
            assertThat(request.options()).containsEntry("list_id", LIST_ID);
        });
    }

    @Test
    void resolveFetchRequest_missingEncryptedCredential_throwsConfigurationError() {
        assertThatThrownBy(() -> collector.resolveFetchRequest(
                clickUpRow(Map.of("workspace_id", WORKSPACE_ID, "list_id", LIST_ID), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing encrypted credential for provider: clickup");
    }

    @Test
    void resolveFetchRequest_brokenCredentialJson_throwsIllegalStateException() {
        when(credentialCryptoService.decrypt(CLICKUP_TOKEN)).thenReturn("not-valid-json");

        assertThatThrownBy(() -> collector.resolveFetchRequest(
                clickUpRow(Map.of("workspace_id", WORKSPACE_ID, "list_id", LIST_ID), CLICKUP_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse ClickUp credential JSON.");
    }

    @Test
    @DisplayName("external_ref에 workspace_id/list_id가 없으면(선택 미완 pending) 설정 오류로 신호한다 — Asana의 " +
            "project_gid 부재 처리를 미러한다")
    void resolveFetchRequest_missingWorkspaceId_throwsConfigurationError() {
        when(credentialCryptoService.decrypt(CLICKUP_TOKEN)).thenReturn(credentialJson("clickup-access-token"));

        assertThatThrownBy(() -> collector.resolveFetchRequest(clickUpRow(Map.of(), CLICKUP_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: workspace_id");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private ProjectIntegrationRepository.IntegrationRow clickUpRow(Map<String, Object> externalRef, byte[] credential) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "clickup", externalRef, credential, null, null);
    }

    private ClickUpRawService.ClickUpFetchContext fetchContext() {
        return new ClickUpRawService.ClickUpFetchContext(
                WebClient.builder().build(), "Bearer token", WORKSPACE_ID, LIST_ID, Instant.EPOCH);
    }

    private String credentialJson(String accessToken) {
        return "{\"access_token\":\"" + accessToken + "\"}";
    }

    private Map<String, Object> buildTaskPageBody(String id, String dateUpdatedMs) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", id);
        task.put("custom_id", null);
        task.put("name", "title-" + id);
        task.put("text_content", "");
        task.put("status", Map.of("status", "open", "type", "open"));
        task.put("date_created", dateUpdatedMs);
        task.put("date_updated", dateUpdatedMs);
        task.put("date_closed", null);
        task.put("date_done", null);
        task.put("creator", null);
        task.put("assignees", null);
        task.put("parent", null);
        task.put("priority", null);

        Map<String, Object> body = new HashMap<>();
        body.put("tasks", List.of(task));
        return body;
    }
}
