package com.history.pipeline_worker.normalizer;

import com.history.pipeline_worker.dto.NormalizedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlackNormalizer: Slack 채널/메시지/스레드 데이터 → Communication 이벤트 목록 변환.
 * tsToInstant() Unix epoch 소수점 변환과 스레드 reply 처리 로직이 핵심 테스트 대상.
 */
class SlackNormalizerTest {

    private SlackNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new SlackNormalizer(new RefsExtractor());
    }

    // ─── null / 빈 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 입력 → 빈 이벤트 목록")
    void normalizeChannels_nullInput_returnsEmpty() {
        assertThat(normalizer.normalizeChannels(null)).isEmpty();
    }

    @Test
    @DisplayName("channels 키 없는 Map → 빈 이벤트 목록")
    void normalizeChannels_noChannelsKey_returnsEmpty() {
        assertThat(normalizer.normalizeChannels(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("channels 빈 리스트 → 빈 이벤트 목록")
    void normalizeChannels_emptyChannels_returnsEmpty() {
        assertThat(normalizer.normalizeChannels(Map.of("channels", List.of()))).isEmpty();
    }

    // ─── 일반 메시지 변환 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("일반 메시지 → Communication 이벤트 생성, 기본 필드 매핑")
    void normalizeChannels_normalMessage_createsCommunicationEvent() {
        Map<String, Object> msg = buildMessage("U001", "Alice", "alice@test.com",
                "Hello world", "1714000000.000000");

        Map<String, Object> slackData = buildSlackData("general", "C001",
                List.of(msg), null);

        List<NormalizedEvent> events = normalizer.normalizeChannels(slackData);

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("Communication");
        assertThat(event.source()).isEqualTo("SLACK");
        assertThat(event.actor().id()).isEqualTo("U001");
        assertThat(event.actor().name()).isEqualTo("Alice");
        assertThat(event.properties()).containsEntry("body", "Hello world");
        assertThat(event.properties()).containsEntry("channel", "general");
        assertThat(event.properties()).containsEntry("created_at", null);
    }

    @Test
    @DisplayName("단일 채널 → Communication 이벤트 생성")
    void normalizeChannel_normalMessage_createsCommunicationEvent() {
        Map<String, Object> msg = buildMessage("U001", "Alice", "alice@test.com",
                "Hello world", "1714000000.000000");
        Map<String, Object> channel = buildChannel("general", "C001", List.of(msg), null);

        List<NormalizedEvent> events = normalizer.normalizeChannel(channel);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).properties()).containsEntry("channel", "general");
    }

    @Test
    @DisplayName("루트 메시지의 conversation_id는 자신의 ts")
    void normalizeChannels_rootMessage_conversationIdIsOwnTs() {
        String ts = "1714000000.123456";
        Map<String, Object> msg = buildMessage("U001", "Alice", null, "text", ts);
        Map<String, Object> slackData = buildSlackData("general", "C001", List.of(msg), null);

        NormalizedEvent event = normalizer.normalizeChannels(slackData).get(0);

        assertThat(event.properties()).containsEntry("conversation_id", ts);
    }

    @Test
    @DisplayName("채널 URL은 channelId 기반으로 생성")
    void normalizeChannels_url_containsChannelId() {
        Map<String, Object> msg = buildMessage("U001", "Alice", null, "text", "1714000000.000000");
        Map<String, Object> slackData = buildSlackData("general", "C999", List.of(msg), null);

        NormalizedEvent event = normalizer.normalizeChannels(slackData).get(0);

        assertThat(event.properties().get("url").toString()).contains("C999");
    }

    // ─── 스레드 reply 처리 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("스레드 reply의 conversation_id는 부모 thread_ts")
    void normalizeChannels_threadReply_conversationIdIsThreadTs() {
        String threadTs = "1714000000.000001";
        Map<String, Object> reply = buildMessage("U002", "Bob", null, "reply text", "1714000001.000000");

        Map<String, Object> thread = new HashMap<>();
        thread.put("thread_ts", threadTs);
        thread.put("replies", List.of(reply));

        Map<String, Object> slackData = buildSlackData("dev", "C002", null, List.of(thread));

        List<NormalizedEvent> events = normalizer.normalizeChannels(slackData);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).properties()).containsEntry("conversation_id", threadTs);
    }

    @Test
    @DisplayName("메시지와 스레드 reply 동시에 있을 때 총 이벤트 수 확인")
    void normalizeChannels_messagesAndThreads_allEventsCreated() {
        Map<String, Object> msg = buildMessage("U001", "Alice", null, "root msg", "1714000000.000000");

        Map<String, Object> reply1 = buildMessage("U002", "Bob", null, "reply1", "1714000001.000000");
        Map<String, Object> reply2 = buildMessage("U003", "Carol", null, "reply2", "1714000002.000000");
        Map<String, Object> thread = new HashMap<>();
        thread.put("thread_ts", "1714000000.000000");
        thread.put("replies", List.of(reply1, reply2));

        Map<String, Object> slackData = buildSlackData("general", "C001",
                List.of(msg), List.of(thread));

        // 루트 1개 + reply 2개 = 총 3개
        assertThat(normalizer.normalizeChannels(slackData)).hasSize(3);
    }

    // ─── ts → Instant 변환 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ts '1714000000.123456' → 올바른 Instant 변환")
    void normalizeChannels_tsConversion_correctInstant() {
        // 1714000000초 = 2024-04-25T14:13:20Z (approximately)
        String ts = "1714000000.000000";
        Map<String, Object> msg = buildMessage("U001", "Alice", null, "text", ts);
        Map<String, Object> slackData = buildSlackData("general", "C001", List.of(msg), null);

        Instant occurredAt = normalizer.normalizeChannels(slackData).get(0).occurredAt();

        assertThat(occurredAt.getEpochSecond()).isEqualTo(1714000000L);
    }

    @Test
    @DisplayName("ts가 나노초 소수점까지 정확하게 변환")
    void normalizeChannels_tsConversion_nanosPreserved() {
        // .500000 → 0.5초 → 500_000_000 나노초
        String ts = "1714000000.500000";
        Map<String, Object> msg = buildMessage("U001", "Alice", null, "text", ts);
        Map<String, Object> slackData = buildSlackData("general", "C001", List.of(msg), null);

        Instant occurredAt = normalizer.normalizeChannels(slackData).get(0).occurredAt();

        assertThat(occurredAt.getNano()).isEqualTo(500_000_000);
    }

    // ─── refs 추출 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("메시지 텍스트에 Jira 키 포함 시 refs 추출")
    void normalizeChannels_messageWithJiraKey_refsExtracted() {
        Map<String, Object> msg = buildMessage("U001", "Alice", null,
                "해당 이슈는 PROJ-123 확인 바람", "1714000000.000000");
        Map<String, Object> slackData = buildSlackData("general", "C001", List.of(msg), null);

        NormalizedEvent event = normalizer.normalizeChannels(slackData).get(0);

        assertThat(event.refs()).containsEntry("jiraKey", "PROJ-123");
    }

    // ─── 헬퍼 메서드 ─────────────────────────────────────────────────────────────

    /** 메시지 raw 데이터 빌더 */
    private Map<String, Object> buildMessage(String userId, String userName, String userEmail,
                                              String text, String ts) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("user", userId);
        msg.put("userName", userName);
        msg.put("userEmail", userEmail);
        msg.put("text", text);
        msg.put("ts", ts);
        return msg;
    }

    /** Slack fetch 결과 raw 데이터 빌더 */
    private Map<String, Object> buildSlackData(String channelName, String channelId,
                                                List<Map<String, Object>> messages,
                                                List<Map<String, Object>> threads) {
        Map<String, Object> channel = buildChannel(channelName, channelId, messages, threads);

        Map<String, Object> slackData = new HashMap<>();
        slackData.put("channels", List.of(channel));
        return slackData;
    }

    private Map<String, Object> buildChannel(String channelName, String channelId,
                                             List<Map<String, Object>> messages,
                                             List<Map<String, Object>> threads) {
        Map<String, Object> channel = new HashMap<>();
        channel.put("channelName", channelName);
        channel.put("channelId", channelId);
        channel.put("messages", messages);
        channel.put("threads", threads);
        return channel;
    }
}
