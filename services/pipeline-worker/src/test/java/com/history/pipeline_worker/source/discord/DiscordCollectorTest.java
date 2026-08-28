package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscordCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);

    @Mock
    private DiscordRawService rawService;
    @Mock
    private DiscordNormalizer normalizer;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;

    private DiscordCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DiscordCollector(rawService, normalizer, eventPublisher, checkpointService, "test-bot-token");
    }

    @Test
    @DisplayName("guild_id가 있으면 봇 토큰과 길드 id를 담은 요청을 만든다")
    void resolveFetchRequest_wrapsBotTokenWithGuildId() {
        Optional<RawFetchRequest> result = collector.resolveFetchRequest(discordRow(Map.of("guild_id", "G1")));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bot test-bot-token");
            assertThat(request.projectKey()).isEqualTo("G1");
        });
    }

    @Test
    @DisplayName("guild_id가 없으면 설정 오류로 예외를 던진다")
    void resolveFetchRequest_missingGuildId_throwsConfigurationError() {
        assertThatThrownBy(() -> collector.resolveFetchRequest(discordRow(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing guild_id for provider: discord");
    }

    @Test
    @DisplayName("봇 토큰이 빈 문자열이면(application.yaml의 ${DISCORD_BOT_TOKEN:} 기본값) guild_id가 있어도 즉시 예외 — 수집 시점 401로 미루지 않는다")
    void resolveFetchRequest_blankBotToken_throwsConfigurationErrorImmediately() {
        DiscordCollector collectorWithoutToken =
                new DiscordCollector(rawService, normalizer, eventPublisher, checkpointService, "");

        assertThatThrownBy(() -> collectorWithoutToken.resolveFetchRequest(discordRow(Map.of("guild_id", "G1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot-token");
        verifyNoInteractions(rawService);
    }

    @Test
    @DisplayName("채널별로 발행하고 전체 최대 occurredAt으로 커서를 한 번 갱신한다")
    void collect_publishesPerChannelAndAdvancesCursorOnce() {
        RawFetchRequest request = new RawFetchRequest("Bot test-bot-token", "G1", Map.of());
        DiscordRawService.DiscordFetchContext context =
                new DiscordRawService.DiscordFetchContext("Bot test-bot-token", "G1", null, PROJECT_ID);
        Map<String, Object> channel1 = Map.of("id", "C1", "name", "일반", "isThread", false);
        Map<String, Object> channel2 = Map.of("id", "C2", "name", "개발", "isThread", false);
        List<Map<String, Object>> rawMessages1 = List.of(Map.of("id", "M1"));
        List<Map<String, Object>> rawMessages2 = List.of(Map.of("id", "M2"));
        List<NormalizedEvent> events1 = List.of(event(Instant.parse("2026-08-08T00:00:00Z")));
        List<NormalizedEvent> events2 = List.of(event(Instant.parse("2026-08-08T01:00:00Z")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.DISCORD)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel1, channel2));
        when(rawService.fetchMessagePage(context, channel1, null))
                .thenReturn(new DiscordRawService.DiscordMessagePage(rawMessages1, null));
        when(rawService.fetchMessagePage(context, channel2, null))
                .thenReturn(new DiscordRawService.DiscordMessagePage(rawMessages2, null));
        when(normalizer.normalizeChannel(eq(PROJECT_ID), eq("G1"), eq(channel1), eq(rawMessages1), anyMap()))
                .thenReturn(events1);
        when(normalizer.normalizeChannel(eq(PROJECT_ID), eq("G1"), eq(channel2), eq(rawMessages2), anyMap()))
                .thenReturn(events2);
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int published = collector.collect(PROJECT_ID, request);

        assertThat(published).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.DISCORD,
                DiscordCollector.MESSAGES_CURSOR, Instant.parse("2026-08-08T01:00:00Z"));
    }

    @Test
    @DisplayName("여러 페이지짜리 채널은 페이지마다 발행한다 — 채널 전체를 모아 한 번에 보내지 않는다")
    void collect_publishesPerPageNotPerChannel() {
        RawFetchRequest request = new RawFetchRequest("Bot test-bot-token", "G1", Map.of());
        DiscordRawService.DiscordFetchContext context =
                new DiscordRawService.DiscordFetchContext("Bot test-bot-token", "G1", null, PROJECT_ID);
        Map<String, Object> channel = Map.of("id", "C1", "name", "일반", "isThread", false);
        List<Map<String, Object>> rawPage1 = List.of(Map.of("id", "M1"));
        List<Map<String, Object>> rawPage2 = List.of(Map.of("id", "M2"));
        List<NormalizedEvent> events1 = List.of(event(Instant.parse("2026-08-08T00:00:00Z")));
        List<NormalizedEvent> events2 = List.of(event(Instant.parse("2026-08-08T02:00:00Z")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.DISCORD)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(channel));
        when(rawService.fetchMessagePage(context, channel, null))
                .thenReturn(new DiscordRawService.DiscordMessagePage(rawPage1, "CURSOR1"));
        when(rawService.fetchMessagePage(context, channel, "CURSOR1"))
                .thenReturn(new DiscordRawService.DiscordMessagePage(rawPage2, null));
        when(normalizer.normalizeChannel(eq(PROJECT_ID), eq("G1"), eq(channel), eq(rawPage1), anyMap()))
                .thenReturn(events1);
        when(normalizer.normalizeChannel(eq(PROJECT_ID), eq("G1"), eq(channel), eq(rawPage2), anyMap()))
                .thenReturn(events2);
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int published = collector.collect(PROJECT_ID, request);

        assertThat(published).isEqualTo(2);
        // 채널은 하나인데 발행은 두 번 — 발행 배치가 채널 크기가 아니라 페이지 크기에 묶인다
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.DISCORD,
                DiscordCollector.MESSAGES_CURSOR, Instant.parse("2026-08-08T02:00:00Z"));
    }

    @Test
    @DisplayName("봇이 접근 못하는 채널(403)은 건너뛰고 나머지 채널 수집은 계속한다")
    void collect_skipsForbiddenChannelAndContinues() {
        RawFetchRequest request = new RawFetchRequest("Bot test-bot-token", "G1", Map.of());
        DiscordRawService.DiscordFetchContext context =
                new DiscordRawService.DiscordFetchContext("Bot test-bot-token", "G1", null, PROJECT_ID);
        Map<String, Object> forbiddenChannel = Map.of("id", "C1", "name", "비공개", "isThread", false);
        Map<String, Object> okChannel = Map.of("id", "C2", "name", "일반", "isThread", false);
        List<Map<String, Object>> rawMessages = List.of(Map.of("id", "M1"));
        List<NormalizedEvent> events = List.of(event(Instant.parse("2026-08-08T00:00:00Z")));

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.DISCORD)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenReturn(List.of(forbiddenChannel, okChannel));
        when(rawService.fetchMessagePage(context, forbiddenChannel, null)).thenThrow(
                WebClientResponseException.create(HttpStatus.FORBIDDEN.value(), "Forbidden", null, null, null));
        when(rawService.fetchMessagePage(context, okChannel, null))
                .thenReturn(new DiscordRawService.DiscordMessagePage(rawMessages, null));
        when(normalizer.normalizeChannel(eq(PROJECT_ID), eq("G1"), eq(okChannel), eq(rawMessages), anyMap()))
                .thenReturn(events);
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int published = collector.collect(PROJECT_ID, request);

        assertThat(published).isEqualTo(1);
        verify(eventPublisher, times(1)).publishAll(anyList());
        verify(normalizer, never())
                .normalizeChannel(eq(PROJECT_ID), eq("G1"), eq(forbiddenChannel), any(), anyMap());
    }

    @Test
    @DisplayName("길드 접근 불가(403 — 봇이 추방된 경우)면 이번 실행은 Discord만 건너뛰고 checkpoint를 전진시키지 않는다")
    void collect_guildForbidden_skipsRunWithoutAdvancingCheckpoint() {
        RawFetchRequest request = new RawFetchRequest("Bot test-bot-token", "G1", Map.of());
        DiscordRawService.DiscordFetchContext context =
                new DiscordRawService.DiscordFetchContext("Bot test-bot-token", "G1", null, PROJECT_ID);

        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.DISCORD)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null, PROJECT_ID)).thenReturn(context);
        when(rawService.fetchChannels(context)).thenThrow(
                WebClientResponseException.create(HttpStatus.FORBIDDEN.value(), "Forbidden", null, null, null));

        int published = collector.collect(PROJECT_ID, request);

        assertThat(published).isZero();
        verify(eventPublisher, never()).publishAll(anyList());
        verify(checkpointService, never()).updateCursor(any(), any(), any(), any());
    }

    @Test
    void collect_passesStoredCursorToFetchContext() {
        RawFetchRequest request = new RawFetchRequest("Bot test-bot-token", "G1", Map.of());
        Instant lastScannedAt = Instant.parse("2026-08-01T00:00:00Z");
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.DISCORD))
                .thenReturn(Map.of(DiscordCollector.MESSAGES_CURSOR, lastScannedAt));
        when(rawService.prepareFetchContext(request, lastScannedAt, PROJECT_ID))
                .thenReturn(new DiscordRawService.DiscordFetchContext("Bot test-bot-token", "G1", lastScannedAt, PROJECT_ID));
        when(rawService.fetchChannels(any())).thenReturn(List.of());

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, lastScannedAt, PROJECT_ID);
    }

    private ProjectIntegrationRepository.IntegrationRow discordRow(Map<String, Object> externalRef) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "discord", externalRef, new byte[] {1}, null, null);
    }

    private NormalizedEvent event(Instant occurredAt) {
        return new NormalizedEvent(PROJECT_ID, "Communication", "DISCORD", occurredAt,
                new ActorDto("U1", "Alice", null), Map.of(), Map.of());
    }
}
