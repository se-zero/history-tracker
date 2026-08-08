package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiscordNormalizer: Discord 채널/메시지 raw 데이터 → Communication 이벤트 변환.
 * conversation_id 3분기(스레드/답글/루트)와 멘션 치환 로직이 핵심 테스트 대상.
 */
class DiscordNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GUILD_ID = "G1";

    private DiscordNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new DiscordNormalizer(new RefsExtractor());
    }

    @Test
    @DisplayName("null 메시지 목록 → 빈 이벤트 목록")
    void normalizeChannel_nullMessages_returnsEmpty() {
        assertThat(normalizer.normalizeChannel(PROJECT_ID, GUILD_ID, channel("일반", "C1", false), null)).isEmpty();
    }

    @Test
    @DisplayName("일반 메시지 → Communication 이벤트 생성, 기본 필드 매핑")
    void normalizeChannel_normalMessage_createsCommunicationEvent() {
        Map<String, Object> message = message("M1", "U1", "Alice", "Hello world",
                "2026-08-08T05:25:48.536000+00:00", 0);
        Map<String, Object> channel = channel("일반", "C1", false);

        List<NormalizedEvent> events = normalizer.normalizeChannel(PROJECT_ID, GUILD_ID, channel, List.of(message));

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("Communication");
        assertThat(event.source()).isEqualTo("DISCORD");
        assertThat(event.actor().id()).isEqualTo("U1");
        assertThat(event.actor().name()).isEqualTo("Alice");
        // 봇은 타인의 이메일을 얻을 수 없다 — 항상 null
        assertThat(event.actor().email()).isNull();
        assertThat(event.properties()).containsEntry("body", "Hello world");
        assertThat(event.properties()).containsEntry("channel", "일반");
        assertThat(event.properties()).containsEntry("created_at", "2026-08-08T05:25:48.536000+00:00");
    }

    @Test
    @DisplayName("actor.name은 global_name 우선, 없으면 username")
    void normalizeChannel_actorName_prefersGlobalNameOverUsername() {
        Map<String, Object> author = new HashMap<>();
        author.put("id", "U1");
        author.put("username", "raw_username");
        author.put("global_name", null);
        Map<String, Object> message = messageWithAuthor("M1", author, "text",
                "2026-08-08T05:25:48.536000+00:00", 0);

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.actor().name()).isEqualTo("raw_username");
    }

    @Test
    @DisplayName("메시지 URL은 guildId·channelId·messageId 기반 딥링크로 조립된다")
    void normalizeChannel_url_isAssembledDeepLink() {
        Map<String, Object> message = message("999", "U1", "Alice", "text",
                "2026-08-08T05:25:48.536000+00:00", 0);

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.properties().get("url")).isEqualTo("https://discord.com/channels/G1/C1/999");
    }

    @Test
    @DisplayName("일반 채널의 루트 메시지 → conversation_id는 자신의 id")
    void normalizeChannel_rootMessageInRegularChannel_conversationIdIsOwnId() {
        Map<String, Object> message = message("M1", "U1", "Alice", "text",
                "2026-08-08T05:25:48.536000+00:00", 0);

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.properties()).containsEntry("conversation_id", "M1");
    }

    @Test
    @DisplayName("일반 채널의 답글(type 19) → conversation_id는 message_reference.message_id")
    void normalizeChannel_replyInRegularChannel_conversationIdIsReferencedMessageId() {
        Map<String, Object> message = message("M2", "U2", "Bob", "reply text",
                "2026-08-08T05:26:00.000000+00:00", 19);
        message.put("message_reference", Map.of("message_id", "M1"));

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.properties()).containsEntry("conversation_id", "M1");
    }

    @Test
    @DisplayName("스레드 채널 안의 메시지는 타입과 무관하게 스레드(채널) id로 묶인다")
    void normalizeChannel_messageInThread_conversationIdIsThreadChannelId() {
        Map<String, Object> rootLikeMessage = message("M10", "U1", "Alice", "스레드 첫 메시지",
                "2026-08-08T05:25:48.536000+00:00", 0);
        Map<String, Object> anotherMessage = message("M11", "U2", "Bob", "스레드 두번째 메시지",
                "2026-08-08T05:26:00.000000+00:00", 0);
        Map<String, Object> threadChannel = channel("스레드: 기획 논의", "T1", true);

        List<NormalizedEvent> events = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, threadChannel, List.of(rootLikeMessage, anotherMessage));

        assertThat(events).extracting(event -> event.properties().get("conversation_id"))
                .containsExactly("T1", "T1");
    }

    @Test
    @DisplayName("<@id> 실제 멘션은 mentions 배열의 표시 이름으로 치환된다")
    void normalizeChannel_realMention_substitutedWithDisplayName() {
        Map<String, Object> mentionedUser = new HashMap<>();
        mentionedUser.put("id", "1535516144784642048");
        mentionedUser.put("username", "bob");
        mentionedUser.put("global_name", "Bob Kim");

        Map<String, Object> message = message("M1", "U1", "Alice", "<@1535516144784642048> 확인 부탁해요",
                "2026-08-08T05:25:48.536000+00:00", 0);
        message.put("mentions", List.of(mentionedUser));

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.properties()).containsEntry("body", "@Bob Kim 확인 부탁해요");
    }

    @Test
    @DisplayName("자동완성 없이 타이핑한 평문 @이름은 mentions가 비어 있어 그대로 남는다 (실측 확인)")
    void normalizeChannel_plainAtTextWithoutMentionEntry_staysUnchanged() {
        Map<String, Object> message = message("M1", "U1", "Alice", "@김재민 확인해주세요",
                "2026-08-08T05:25:48.536000+00:00", 0);
        message.put("mentions", List.of());

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.properties()).containsEntry("body", "@김재민 확인해주세요");
    }

    @Test
    @DisplayName("Discord timestamp(+00:00 오프셋)가 Instant로 정확히 변환된다")
    void normalizeChannel_timestamp_parsedWithOffset() {
        Map<String, Object> message = message("M1", "U1", "Alice", "text",
                "2026-08-08T06:16:44.423000+00:00", 0);

        Instant occurredAt = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0).occurredAt();

        assertThat(occurredAt).isEqualTo(Instant.parse("2026-08-08T06:16:44.423000Z"));
    }

    @Test
    @DisplayName("본문에 이슈 키가 있으면 refs를 추출한다")
    void normalizeChannel_messageWithIssueKey_refsExtracted() {
        Map<String, Object> message = message("M1", "U1", "Alice", "해당 이슈는 HT-123 확인 바람",
                "2026-08-08T05:25:48.536000+00:00", 0);

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message)).get(0);

        assertThat(event.refs()).containsEntry("issueKey", "HT-123");
    }

    private Map<String, Object> channel(String name, String id, boolean isThread) {
        Map<String, Object> channel = new HashMap<>();
        channel.put("id", id);
        channel.put("name", name);
        channel.put("isThread", isThread);
        return channel;
    }

    private Map<String, Object> message(String id, String authorId, String authorName, String content,
                                         String timestamp, int type) {
        Map<String, Object> author = new HashMap<>();
        author.put("id", authorId);
        author.put("username", authorName);
        author.put("global_name", authorName);
        return messageWithAuthor(id, author, content, timestamp, type);
    }

    private Map<String, Object> messageWithAuthor(String id, Map<String, Object> author, String content,
                                                    String timestamp, int type) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("author", author);
        message.put("content", content);
        message.put("timestamp", timestamp);
        message.put("type", type);
        return message;
    }
}
