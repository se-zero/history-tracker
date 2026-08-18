package com.history.pipeline_worker.source.slack;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlackCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final byte[] SLACK_TOKEN = new byte[] {3};

    @Mock
    private SlackRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private SlackCollector collector;

    @BeforeEach
    void setUp() {
        collector = new SlackCollector(
                rawService,
                new SlackNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService
        );
    }

    @Test
    void collect_passesStoredCursorToFetchHistoryPage() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant lastScannedAt = Instant.parse("2024-03-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(lastScannedAt)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(rawService).fetchHistoryPage(context, channel, null, lastScannedAt);
    }

    @Test
    @DisplayName("채널마다 완주 직후 자기 메시지의 최대 occurredAt으로 커서를 갱신한다 (전역 키는 갱신하지 않음)")
    void collect_advancesCursorPerChannelOnCompletion() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Map<String, Object> channel1 = Map.of("id", "C001", "name", "general");
        Map<String, Object> channel2 = Map.of("id", "C002", "name", "dev");
        Map<String, Object> firstChannelData = buildChannel(
                "general", "C001", List.of(buildMessage("U001", "first", "1714000000.000000")));
        Map<String, Object> secondChannelData = buildChannel(
                "dev", "C002", List.of(buildMessage("U002", "second", "1714000100.000000")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel1, channel2));
        when(rawService.fetchHistoryPage(eq(context), eq(channel1), isNull(), isNull()))
                .thenReturn(new SlackRawService.SlackHistoryPage(firstChannelData, null));
        when(rawService.fetchHistoryPage(eq(context), eq(channel2), isNull(), isNull()))
                .thenReturn(new SlackRawService.SlackHistoryPage(secondChannelData, null));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(2);
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C001", Instant.ofEpochSecond(1714000000L));
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C002", Instant.ofEpochSecond(1714000100L));
        verify(checkpointService, never()).updateCursor(eq(PROJECT_ID), eq(CollectionProvider.SLACK),
                eq(SlackCollector.MESSAGES_CURSOR), any());
    }

    @Test
    @DisplayName("채널 전용 커서가 없으면 레거시 전역 커서로 fetchHistoryPage를 호출한다")
    void collect_channelWithoutOwnCursor_fallsBackToLegacyCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant legacyCursor = Instant.parse("2024-01-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, legacyCursor));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(legacyCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(rawService).fetchHistoryPage(context, channel, null, legacyCursor);
    }

    @Test
    @DisplayName("채널 전용 커서가 있으면 레거시 전역 커서보다 우선한다")
    void collect_channelWithOwnCursor_prefersItOverLegacy() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant legacyCursor = Instant.parse("2024-01-01T00:00:00Z");
        Instant channelCursor = Instant.parse("2024-02-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK)).thenReturn(Map.of(
                SlackCollector.MESSAGES_CURSOR, legacyCursor,
                SlackCollector.MESSAGES_CURSOR + ":C001", channelCursor
        ));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(channelCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(rawService).fetchHistoryPage(context, channel, null, channelCursor);
    }

    @Test
    @DisplayName("이벤트 0건 채널이라도 레거시 커서가 있으면 채널 키를 그 값으로 시드한다")
    void collect_emptyChannelWithLegacyCursor_seedsChannelKeyWithLegacyValue() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant legacyCursor = Instant.parse("2024-01-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, legacyCursor));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(legacyCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C001", legacyCursor);
    }

    @Test
    @DisplayName("어떤 커서도 없는 빈 채널은 updateCursor를 null로 호출한다(null no-op은 CheckpointService 계약)")
    void collect_emptyChannelWithoutAnyCursor_writesNullCursorNoop() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), isNull()))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C001", null);
    }

    @Test
    @DisplayName("두 번째 채널 발행이 실패해도 예외가 전파되고 첫 채널의 커서는 이미 저장돼 보존된다")
    void collect_secondChannelPublishFails_firstChannelCursorAlreadySaved() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Map<String, Object> channel1 = Map.of("id", "C001", "name", "general");
        Map<String, Object> channel2 = Map.of("id", "C002", "name", "dev");
        Map<String, Object> firstChannelData = buildChannel(
                "general", "C001", List.of(buildMessage("U001", "first", "1714000000.000000")));
        Map<String, Object> secondChannelData = buildChannel(
                "dev", "C002", List.of(buildMessage("U002", "second", "1714000100.000000")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel1, channel2));
        when(rawService.fetchHistoryPage(eq(context), eq(channel1), isNull(), isNull()))
                .thenReturn(new SlackRawService.SlackHistoryPage(firstChannelData, null));
        when(rawService.fetchHistoryPage(eq(context), eq(channel2), isNull(), isNull()))
                .thenReturn(new SlackRawService.SlackHistoryPage(secondChannelData, null));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size())
                .thenThrow(new EventPublishException("브로커가 메시지를 ack하지 않음"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(EventPublishException.class);

        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C001", Instant.ofEpochSecond(1714000000L));
        verify(checkpointService, never()).updateCursor(eq(PROJECT_ID), eq(CollectionProvider.SLACK),
                eq(SlackCollector.MESSAGES_CURSOR + ":C002"), any());
    }

    @Test
    @DisplayName("fetchChannels 직후 채널 목록에 없는 채널 전용 커서(고아)는 삭제한다")
    void collect_deletesOrphanChannelCursors() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant channelCursor = Instant.parse("2024-03-01T00:00:00Z");
        Instant orphanCursor = Instant.parse("2024-02-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK)).thenReturn(Map.of(
                SlackCollector.MESSAGES_CURSOR + ":C001", channelCursor,
                SlackCollector.MESSAGES_CURSOR + ":C999", orphanCursor
        ));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(channelCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(checkpointService).deleteCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C999");
        verify(checkpointService, never()).deleteCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR + ":C001");
    }

    @Test
    @DisplayName("레거시 전역 커서가 있고 채널 루프가 예외 없이 완주하면 레거시 커서를 삭제한다")
    void collect_afterFullRun_deletesLegacyGlobalCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant legacyCursor = Instant.parse("2024-01-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, legacyCursor));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(legacyCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(checkpointService).deleteCursor(PROJECT_ID, CollectionProvider.SLACK, SlackCollector.MESSAGES_CURSOR);
    }

    @Test
    @DisplayName("채널 루프 중간에 예외가 나면 레거시 전역 커서를 삭제하지 않는다")
    void collect_failureMidRun_keepsLegacyCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant legacyCursor = Instant.parse("2024-01-01T00:00:00Z");
        Map<String, Object> channel1 = Map.of("id", "C001", "name", "general");
        Map<String, Object> channel2 = Map.of("id", "C002", "name", "dev");
        Map<String, Object> firstChannelData = buildChannel(
                "general", "C001", List.of(buildMessage("U001", "first", "1714000000.000000")));
        Map<String, Object> secondChannelData = buildChannel(
                "dev", "C002", List.of(buildMessage("U002", "second", "1714000100.000000")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, legacyCursor));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel1, channel2));
        when(rawService.fetchHistoryPage(eq(context), eq(channel1), isNull(), eq(legacyCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(firstChannelData, null));
        when(rawService.fetchHistoryPage(eq(context), eq(channel2), isNull(), eq(legacyCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(secondChannelData, null));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size())
                .thenThrow(new EventPublishException("브로커가 메시지를 ack하지 않음"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(EventPublishException.class);

        verify(checkpointService, never()).deleteCursor(PROJECT_ID, CollectionProvider.SLACK, SlackCollector.MESSAGES_CURSOR);
    }

    @Test
    @DisplayName("레거시 전역 커서가 없으면(채널 전용 커서만 있어도) 레거시 삭제를 시도하지 않는다")
    void collect_withoutLegacyCursor_skipsLegacyDeletion() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant channelCursor = Instant.parse("2024-02-01T00:00:00Z");
        Map<String, Object> channel = Map.of("id", "C001", "name", "general");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR + ":C001", channelCursor));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchHistoryPage(eq(context), eq(channel), isNull(), eq(channelCursor)))
                .thenReturn(new SlackRawService.SlackHistoryPage(buildChannel("general", "C001", List.of()), null));

        collector.collect(PROJECT_ID, request);

        verify(checkpointService, never()).deleteCursor(PROJECT_ID, CollectionProvider.SLACK, SlackCollector.MESSAGES_CURSOR);
    }

    @Test
    @DisplayName("fetchChannels가 빈 목록을 반환하면 레거시 전역 커서를 삭제하지 않는다")
    void collect_emptyChannelList_keepsLegacyCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Instant legacyCursor = Instant.parse("2024-01-01T00:00:00Z");

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, legacyCursor));
        when(rawService.prepareFetchContext(request)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of());

        collector.collect(PROJECT_ID, request);

        verify(checkpointService, never()).deleteCursor(PROJECT_ID, CollectionProvider.SLACK, SlackCollector.MESSAGES_CURSOR);
    }

    @Test
    void resolveFetchRequest_wrapsDecryptedTokenAsBearer() {
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(slackRow(SLACK_TOKEN));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer xoxb-slack-token");
            assertThat(request.projectKey()).isNull();
        });
    }

    @Test
    void resolveFetchRequest_missingCredential_throwsConfigurationError() {
        assertThatThrownBy(() -> collector.resolveFetchRequest(slackRow(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing encrypted credential for provider: slack");
    }

    private ProjectIntegrationRepository.IntegrationRow slackRow(byte[] credential) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "slack", Map.of("workspace_id", "T123"), credential, null, null);
    }

    private SlackRawService.SlackFetchContext fetchContext() {
        return new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());
    }

    private Map<String, Object> buildChannel(String name, String id, List<Map<String, Object>> messages) {
        Map<String, Object> channel = new HashMap<>();
        channel.put("channelName", name);
        channel.put("channelId", id);
        channel.put("messages", messages);
        channel.put("threads", List.of());
        return channel;
    }

    private Map<String, Object> buildMessage(String userId, String text, String ts) {
        Map<String, Object> message = new HashMap<>();
        message.put("user", userId);
        message.put("text", text);
        message.put("ts", ts);
        return message;
    }
}
