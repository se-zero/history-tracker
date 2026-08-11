package com.history.pipeline_worker.source.linear;

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
 * LinearCollector: JiraCollectorTest 컨벤션(MockitoExtension + 실 LinearNormalizer)을 미러한다.
 *
 * <p>checkpoint는 Jira가 아니라 Slack 패턴을 따른다 — Linear의 {@code orderBy: updatedAt}은
 * 최신 우선(내림차순)이고 방향 제어 인자가 없어서, Jira처럼 페이지마다 커서를 전진시키면 페이지
 * 상한(limitReached)에 걸렸을 때 아직 못 본 더 과거의 이슈들이 다음 수집에서 영구히 건너뛰어진다.
 * 그래서 전체 실행이 끝까지 성공한 경우에만 배치 전체의 최대 occurredAt으로 커서를 한 번 전진시킨다.</p>
 */
@ExtendWith(MockitoExtension.class)
class LinearCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final byte[] LINEAR_TOKEN = new byte[] {4};
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private LinearRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private LinearCollector collector;

    @BeforeEach
    void setUp() {
        collector = new LinearCollector(
                rawService,
                new LinearNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService,
                new ObjectMapper(),
                CLOCK
        );
    }

    // ─── collect: checkpoint (Slack 패턴 — 전체 실행 성공 후 배치 최대 occurredAt으로 1회 전진) ────

    @Test
    @DisplayName("여러 페이지를 발행한 뒤 전체 배치의 최대 occurredAt으로 커서를 한 번만 갱신한다 — " +
            "Linear는 updatedAt 내림차순(최신 우선)이라 첫 페이지의 occurredAt이 배치 전체의 최댓값이다")
    void collect_multiplePages_advancesCursorOnceWithBatchMaxOccurredAt() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "TEAM-1", Map.of());
        LinearRawService.LinearFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.LINEAR)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchIssuePage(context, null, 1)).thenReturn(new LinearRawService.LinearIssuePage(
                Map.of("issues", List.of(buildIssueNode("id-1", "ENG-1", "2024-05-10T00:00:00.000Z"))),
                true, "cursor-2", false));
        when(rawService.fetchIssuePage(context, "cursor-2", 2)).thenReturn(new LinearRawService.LinearIssuePage(
                Map.of("issues", List.of(buildIssueNode("id-2", "ENG-2", "2024-05-01T00:00:00.000Z"))),
                false, null, false));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointService, times(1)).updateCursor(PROJECT_ID, CollectionProvider.LINEAR,
                LinearCollector.UPDATED_CURSOR, Instant.parse("2024-05-10T00:00:00Z"));
    }

    @Test
    @DisplayName("max pages 도달(첫 페이지) 시 발행하지 않고 정상 종료한다(예외 아님), 커서도 전진하지 않는다")
    void collect_limitReachedOnFirstPage_stopsWithoutPublishingOrAdvancingCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "TEAM-1", Map.of());
        LinearRawService.LinearFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.LINEAR)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchIssuePage(context, null, 1))
                .thenReturn(new LinearRawService.LinearIssuePage(Map.of("issues", List.of()), false, null, true));

        assertThat(collector.collect(PROJECT_ID, request)).isZero();

        verifyNoInteractions(eventPublisher);
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("이전 페이지는 이미 발행했는데 다음 페이지에서 max pages에 걸리면, 그 실행 전체의 커서는 " +
            "전진시키지 않는다 — 부분 전진은 상한 아래의 미처리(더 과거) 이슈를 다음 수집에서 영구히 건너뛰게 한다")
    void collect_limitReachedAfterEarlierPagePublished_stillDoesNotAdvanceCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "TEAM-1", Map.of());
        LinearRawService.LinearFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.LINEAR)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchIssuePage(context, null, 1)).thenReturn(new LinearRawService.LinearIssuePage(
                Map.of("issues", List.of(buildIssueNode("id-1", "ENG-1", "2024-05-10T00:00:00.000Z"))),
                true, "cursor-2", false));
        when(rawService.fetchIssuePage(context, "cursor-2", 2))
                .thenReturn(new LinearRawService.LinearIssuePage(Map.of("issues", List.of()), false, null, true));
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
        RawFetchRequest request = new RawFetchRequest("Bearer token", "TEAM-1", Map.of());
        LinearRawService.LinearFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.LINEAR)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchIssuePage(context, null, 1)).thenReturn(new LinearRawService.LinearIssuePage(
                Map.of("issues", List.of(buildIssueNode("id-1", "ENG-1", "2024-05-10T00:00:00.000Z"))),
                false, null, false));
        when(eventPublisher.publishAll(anyList())).thenThrow(new EventPublishException("publish failed"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("publish failed");
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("저장된 checkpoint가 없으면 since로 null이 아닌 Instant.EPOCH를 전달한다 " +
            "— GraphQL updatedAt 필터에 gte:null이 들어가면 필터가 사실상 무시돼 매 실행 전체를 다시 훑게 된다")
    void collect_noStoredCursor_passesEpochAsSinceNotNull() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "TEAM-1", Map.of());
        LinearRawService.LinearFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.LINEAR)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, Instant.EPOCH)).thenReturn(context);
        when(rawService.fetchIssuePage(context, null, 1))
                .thenReturn(new LinearRawService.LinearIssuePage(Map.of("issues", List.of()), false, null, false));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, Instant.EPOCH);
    }

    @Test
    void collect_storedCursor_passesStoredCursorAsSince() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "TEAM-1", Map.of());
        Instant lastScannedAt = Instant.parse("2024-03-01T00:00:00Z");
        LinearRawService.LinearFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.LINEAR))
                .thenReturn(Map.of(LinearCollector.UPDATED_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request, lastScannedAt)).thenReturn(context);
        when(rawService.fetchIssuePage(context, null, 1))
                .thenReturn(new LinearRawService.LinearIssuePage(Map.of("issues", List.of()), false, null, false));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, lastScannedAt);
    }

    // ─── resolveFetchRequest ────────────────────────────────────────────────────

    @Test
    void resolveFetchRequest_buildsBearerRequestWithTeamId() {
        when(credentialCryptoService.decrypt(LINEAR_TOKEN))
                .thenReturn(credentialJson("linear-access-token", "2026-01-01T01:00:00Z"));

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                linearRow(Map.of("team_id", "TEAM-1"), LINEAR_TOKEN));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer linear-access-token");
            assertThat(request.projectKey()).isEqualTo("TEAM-1");
            assertThat(request.options()).isEmpty();
        });
    }

    @Test
    @DisplayName("access_token 만료는 설정 오류가 아니라 지금은 수집 불가다 — 예외가 아니라 empty로 신호한다")
    void resolveFetchRequest_expiredAccessToken_isEmpty() {
        when(credentialCryptoService.decrypt(LINEAR_TOKEN))
                .thenReturn(credentialJson("linear-access-token", "2025-12-31T23:59:59Z"));

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                linearRow(Map.of("team_id", "TEAM-1"), LINEAR_TOKEN));

        assertThat(result).isEmpty();
    }

    @Test
    void resolveFetchRequest_missingEncryptedCredential_throwsConfigurationError() {
        assertThatThrownBy(() -> collector.resolveFetchRequest(linearRow(Map.of("team_id", "TEAM-1"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing encrypted credential for provider: linear");
    }

    @Test
    void resolveFetchRequest_brokenCredentialJson_throwsIllegalStateException() {
        when(credentialCryptoService.decrypt(LINEAR_TOKEN)).thenReturn("not-valid-json");

        assertThatThrownBy(() -> collector.resolveFetchRequest(linearRow(Map.of("team_id", "TEAM-1"), LINEAR_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse Linear credential JSON.");
    }

    @Test
    void resolveFetchRequest_missingTeamId_throwsConfigurationError() {
        when(credentialCryptoService.decrypt(LINEAR_TOKEN))
                .thenReturn(credentialJson("linear-access-token", "2026-01-01T01:00:00Z"));

        assertThatThrownBy(() -> collector.resolveFetchRequest(linearRow(Map.of(), LINEAR_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: team_id");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private ProjectIntegrationRepository.IntegrationRow linearRow(Map<String, Object> externalRef, byte[] credential) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "linear", externalRef, credential, null, null);
    }

    private LinearRawService.LinearFetchContext fetchContext() {
        return new LinearRawService.LinearFetchContext(
                WebClient.builder().build(), "Bearer token", "TEAM-1", Instant.EPOCH);
    }

    private String credentialJson(String accessToken, String expiresAt) {
        return "{\"access_token\":\"" + accessToken + "\",\"refresh_token\":\"linear-refresh-token\","
                + "\"expires_at\":\"" + expiresAt + "\"}";
    }

    private Map<String, Object> buildIssueNode(String id, String identifier, String updatedAt) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("identifier", identifier);
        node.put("title", "title-" + identifier);
        node.put("description", "");
        node.put("updatedAt", updatedAt);
        node.put("createdAt", updatedAt);
        node.put("state", Map.of("name", "In Progress", "type", "started"));
        return node;
    }
}
