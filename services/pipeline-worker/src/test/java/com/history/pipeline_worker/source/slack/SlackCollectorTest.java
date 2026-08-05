package com.history.pipeline_worker.source.slack;

import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
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
    @DisplayName("채널별로 발행하고 전체 최대 occurredAt으로 커서를 한 번 갱신한다")
    void collect_publishesPerChannelAndAdvancesCursorOnce() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        SlackRawService.SlackFetchContext context = fetchContext();
        Map<String, Object> firstChannel = buildChannel(
                "general", "C001", List.of(buildMessage("U001", "first", "1714000000.000000")));
        Map<String, Object> secondChannel = buildChannel(
                "dev", "C002", List.of(buildMessage("U002", "second", "1714000100.000000")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(
                Map.of("id", "C001", "name", "general"),
                Map.of("id", "C002", "name", "dev")
        ));
        when(rawService.fetchHistoryPage(context, Map.of("id", "C001", "name", "general"), null))
                .thenReturn(new SlackRawService.SlackHistoryPage(firstChannel, null));
        when(rawService.fetchHistoryPage(context, Map.of("id", "C002", "name", "dev"), null))
                .thenReturn(new SlackRawService.SlackHistoryPage(secondChannel, null));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.SLACK,
                SlackCollector.MESSAGES_CURSOR, Instant.ofEpochSecond(1714000100L));
    }

    @Test
    void collect_passesStoredCursorToFetchContext() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        Instant lastScannedAt = Instant.parse("2024-03-01T00:00:00Z");
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Map.of(SlackCollector.MESSAGES_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request, lastScannedAt)).thenReturn(fetchContext());
        when(rawService.fetchChannels(any())).thenReturn(List.of());

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, lastScannedAt);
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
        return new SlackRawService.SlackFetchContext("Bearer token", null, Map.of());
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
