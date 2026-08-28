package com.history.pipeline_worker.source.googlechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleChatCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final byte[] ENCRYPTED_CREDENTIAL = new byte[] {2};
    private static final String CREDENTIAL_JSON =
            "{\"access_token\":\"gc-access-token\",\"refresh_token\":\"gc-refresh-token\"}";

    @Mock
    private GoogleChatRawService rawService;
    @Mock
    private GoogleChatNormalizer normalizer;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private GoogleChatCollector collector;

    @BeforeEach
    void setUp() {
        collector = new GoogleChatCollector(
                rawService, normalizer, eventPublisher, checkpointService, credentialCryptoService, new ObjectMapper());
    }

    @Test
    @DisplayName("자격증명 JSON의 access_token을 Bearer로, external_ref.space_id를 대상으로 담는다")
    void resolveFetchRequest_buildsBearerRequestWithSpaceId() {
        when(credentialCryptoService.decrypt(ENCRYPTED_CREDENTIAL)).thenReturn(CREDENTIAL_JSON);

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                googleChatRow(Map.of("space_id", "spaces/AAAA")));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer gc-access-token");
            assertThat(request.projectKey()).isEqualTo("spaces/AAAA");
        });
    }

    @Test
    @DisplayName("space_id가 없으면 설정 오류로 예외를 던진다")
    void resolveFetchRequest_missingSpaceId_throwsConfigurationError() {
        when(credentialCryptoService.decrypt(ENCRYPTED_CREDENTIAL)).thenReturn(CREDENTIAL_JSON);

        assertThatThrownBy(() -> collector.resolveFetchRequest(googleChatRow(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: space_id");
    }

    @Test
    @DisplayName("깨진 credential JSON은 IllegalStateException으로 감싼다 — 호출부 안전망을 우회하지 않는다")
    void resolveFetchRequest_brokenCredentialJson_throwsIllegalStateException() {
        when(credentialCryptoService.decrypt(ENCRYPTED_CREDENTIAL)).thenReturn("not-valid-json");

        assertThatThrownBy(() -> collector.resolveFetchRequest(googleChatRow(Map.of("space_id", "spaces/AAAA"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse Google Chat credential JSON.");
    }

    @Test
    @DisplayName("스페이스 이름 조회·메시지 수집·sender 보강·정규화·발행 후 최대 occurredAt으로 커서를 갱신한다")
    void collect_fetchesNormalizesPublishesAndAdvancesCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "spaces/AAAA", Map.of());
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", "spaces/AAAA", null, new HashMap<>(), PROJECT_ID);
        List<Map<String, Object>> rawMessages = List.of(
                Map.of("name", "spaces/AAAA/messages/M1", "sender", Map.of("name", "users/U1", "type", "HUMAN")));
        Map<String, GoogleChatRawService.PersonInfo> actorInfo =
                Map.of("users/U1", new GoogleChatRawService.PersonInfo("Alice", "alice@example.com"));
        List<NormalizedEvent> events = List.of(
                event(Instant.parse("2026-08-08T00:00:00Z")),
                event(Instant.parse("2026-08-08T02:00:00Z")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GOOGLE_CHAT)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchSpaceDisplayName(context)).thenReturn("engineering");
        when(rawService.fetchMessagePage(context, null))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(rawMessages, null));
        when(rawService.resolveSenders(context, Set.of("users/U1"))).thenReturn(actorInfo);
        when(normalizer.normalizeMessages(PROJECT_ID, "engineering", rawMessages, actorInfo)).thenReturn(events);
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int published = collector.collect(PROJECT_ID, request);

        assertThat(published).isEqualTo(2);
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.GOOGLE_CHAT,
                GoogleChatCollector.MESSAGES_CURSOR, Instant.parse("2026-08-08T02:00:00Z"));
    }

    @Test
    @DisplayName("raw 메시지에 등장한 sender 집합만 골라 People API 보강을 요청한다(중복 제거)")
    void collect_resolvesUniqueSendersFromRawMessages() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "spaces/AAAA", Map.of());
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", "spaces/AAAA", null, new HashMap<>(), PROJECT_ID);
        List<Map<String, Object>> rawMessages = List.of(
                Map.of("name", "spaces/AAAA/messages/M1", "sender", Map.of("name", "users/U1", "type", "HUMAN")),
                Map.of("name", "spaces/AAAA/messages/M2", "sender", Map.of("name", "users/U1", "type", "HUMAN")),
                Map.of("name", "spaces/AAAA/messages/M3", "sender", Map.of("name", "users/U2", "type", "HUMAN")),
                Map.of("name", "spaces/AAAA/messages/M4"));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GOOGLE_CHAT)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchSpaceDisplayName(context)).thenReturn("engineering");
        when(rawService.fetchMessagePage(context, null))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(rawMessages, null));
        when(rawService.resolveSenders(any(), anySet())).thenReturn(Map.of());
        when(normalizer.normalizeMessages(any(), any(), any(), any())).thenReturn(List.of());

        collector.collect(PROJECT_ID, request);

        verify(rawService).resolveSenders(context, Set.of("users/U1", "users/U2"));
    }

    @Test
    @DisplayName("여러 페이지짜리 스페이스는 페이지마다 발행하고 checkpoint는 마지막에 한 번만 전진한다")
    void collect_publishesPerPageAndAdvancesCursorOnce() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "spaces/AAAA", Map.of());
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", "spaces/AAAA", null, new HashMap<>(), PROJECT_ID);
        List<Map<String, Object>> rawPage1 = List.of(
                Map.of("name", "spaces/AAAA/messages/M1", "sender", Map.of("name", "users/U1", "type", "HUMAN")));
        List<Map<String, Object>> rawPage2 = List.of(
                Map.of("name", "spaces/AAAA/messages/M2", "sender", Map.of("name", "users/U2", "type", "HUMAN")));
        List<NormalizedEvent> events1 = List.of(event(Instant.parse("2026-08-08T00:00:00Z")));
        List<NormalizedEvent> events2 = List.of(event(Instant.parse("2026-08-08T03:00:00Z")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GOOGLE_CHAT)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchSpaceDisplayName(context)).thenReturn("engineering");
        when(rawService.fetchMessagePage(context, null))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(rawPage1, "page-2"));
        when(rawService.fetchMessagePage(context, "page-2"))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(rawPage2, null));
        when(rawService.resolveSenders(any(), anySet())).thenReturn(Map.of());
        when(normalizer.normalizeMessages(PROJECT_ID, "engineering", rawPage1, Map.of())).thenReturn(events1);
        when(normalizer.normalizeMessages(PROJECT_ID, "engineering", rawPage2, Map.of())).thenReturn(events2);
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int published = collector.collect(PROJECT_ID, request);

        assertThat(published).isEqualTo(2);
        // 스페이스는 하나인데 발행은 두 번 — 발행 배치가 스페이스 크기가 아니라 페이지 크기에 묶인다
        verify(eventPublisher, times(2)).publishAll(anyList());
        // checkpoint는 전체 성공 후 한 번, 전 페이지 최대 occurredAt으로만 전진한다
        verify(checkpointService, times(1)).updateCursor(PROJECT_ID, CollectionProvider.GOOGLE_CHAT,
                GoogleChatCollector.MESSAGES_CURSOR, Instant.parse("2026-08-08T03:00:00Z"));
    }

    @Test
    @DisplayName("중간 페이지 발행이 실패하면 예외가 전파되고 checkpoint는 전진하지 않는다")
    void collect_publishFailureOnLaterPage_doesNotAdvanceCheckpoint() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "spaces/AAAA", Map.of());
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", "spaces/AAAA", null, new HashMap<>(), PROJECT_ID);
        List<Map<String, Object>> rawPage1 = List.of(Map.of("name", "spaces/AAAA/messages/M1"));
        List<Map<String, Object>> rawPage2 = List.of(Map.of("name", "spaces/AAAA/messages/M2"));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GOOGLE_CHAT)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchSpaceDisplayName(context)).thenReturn("engineering");
        when(rawService.fetchMessagePage(context, null))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(rawPage1, "page-2"));
        when(rawService.fetchMessagePage(context, "page-2"))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(rawPage2, null));
        when(rawService.resolveSenders(any(), anySet())).thenReturn(Map.of());
        when(normalizer.normalizeMessages(any(), any(), any(), any()))
                .thenReturn(List.of(event(Instant.parse("2026-08-08T00:00:00Z"))));
        when(eventPublisher.publishAll(anyList()))
                .thenReturn(1)
                .thenThrow(new IllegalStateException("broker nack"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(IllegalStateException.class);

        // 전량 축적하던 때와 같은 보증 — 실패하면 checkpoint가 그대로라 다음 실행에서 재발행된다
        verify(checkpointService, never()).updateCursor(any(), any(), any(), any());
    }

    @Test
    void collect_passesStoredCursorToFetchContext() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "spaces/AAAA", Map.of());
        Instant lastScannedAt = Instant.parse("2026-08-01T00:00:00Z");
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GOOGLE_CHAT))
                .thenReturn(Map.of(GoogleChatCollector.MESSAGES_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request, lastScannedAt, PROJECT_ID)).thenReturn(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", "spaces/AAAA", lastScannedAt, new HashMap<>(), PROJECT_ID));
        when(rawService.fetchMessagePage(any(), any()))
                .thenReturn(new GoogleChatRawService.GoogleChatMessagePage(List.of(), null));
        when(rawService.resolveSenders(any(), anySet())).thenReturn(Map.of());
        when(normalizer.normalizeMessages(eq(PROJECT_ID), any(), anyList(), any())).thenReturn(List.of());

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, lastScannedAt, PROJECT_ID);
    }

    private ProjectIntegrationRepository.IntegrationRow googleChatRow(Map<String, Object> externalRef) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "google-chat", externalRef, ENCRYPTED_CREDENTIAL, null, null);
    }

    private NormalizedEvent event(Instant occurredAt) {
        return new NormalizedEvent(PROJECT_ID, "Communication", "GOOGLE_CHAT", occurredAt,
                new ActorDto("U1", "Alice", null), Map.of(), Map.of());
    }
}
