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
    @DisplayName("답글의 답글(A←B←C)은 한 conversation_id로 접힌다 — 직접 부모만 보면 둘로 쪼개진다")
    void normalizeChannel_replyChain_foldsIntoSingleConversationId() {
        Map<String, Object> messageA = message("1000", "U1", "Alice", "루트 메시지",
                "2026-08-08T05:25:00.000000+00:00", 0);
        Map<String, Object> messageB = reply("1001", "U2", "Bob", "A에 대한 답글", "1000",
                "2026-08-08T05:26:00.000000+00:00");
        Map<String, Object> messageC = reply("1002", "U3", "Carol", "B에 대한 답글", "1001",
                "2026-08-08T05:27:00.000000+00:00");
        // 페이지네이션 안쪽 정렬(최신→과거)을 그대로 흉내낸다 — 정렬 로직이 이 순서에 기대면 안 된다
        List<Map<String, Object>> messagesNewestFirst = List.of(messageC, messageB, messageA);

        List<NormalizedEvent> events = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), messagesNewestFirst);

        assertThat(events).extracting(e -> e.properties().get("conversation_id"))
                .containsOnly("1000");
    }

    @Test
    @DisplayName("답글 체인은 페이지를 가로질러도 접힌다 — 맵을 다음 호출에 그대로 넘겨야 한다")
    void normalizeChannel_replyChainAcrossPages_stillFoldsWhenMapIsCarriedOver() {
        Map<String, Object> messageA = message("2000", "U1", "Alice", "루트 메시지",
                "2026-08-08T05:25:00.000000+00:00", 0);
        Map<String, Object> messageB = reply("2001", "U2", "Bob", "A에 대한 답글", "2000",
                "2026-08-08T05:26:00.000000+00:00");
        Map<String, Object> messageC = reply("2002", "U3", "Carol", "B에 대한 답글", "2001",
                "2026-08-08T05:27:00.000000+00:00");
        Map<String, Object> channel = channel("일반", "C1", false);
        Map<String, String> resolvedConversationIds = new HashMap<>();

        // 1페이지: A, B (오래된 쪽 먼저 — 실제 페이지네이션 순서)
        normalizer.normalizeChannel(PROJECT_ID, GUILD_ID, channel, List.of(messageA, messageB), resolvedConversationIds);
        // 2페이지: C만 — 부모 B는 1페이지에서 이미 처리됐고 맵에만 남아 있다
        List<NormalizedEvent> page2Events = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel, List.of(messageC), resolvedConversationIds);

        assertThat(page2Events.get(0).properties()).containsEntry("conversation_id", "2000");
    }

    @Test
    @DisplayName("부모가 이번 실행에 없으면(다른 실행에서 수집·필터된 메시지) 직접 부모 id로 폴백한다 — 기존 동작과 같다")
    void normalizeChannel_replyToUnknownParent_fallsBackToDirectParentId() {
        Map<String, Object> orphanReply = reply("3001", "U2", "Bob", "이전 실행에서 수집된 메시지에 대한 답글",
                "2999", "2026-08-08T05:26:00.000000+00:00");

        NormalizedEvent event = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(orphanReply)).get(0);

        assertThat(event.properties()).containsEntry("conversation_id", "2999");
    }

    @Test
    @DisplayName("답글이 아닌 정렬 불가능한 id(비-snowflake)도 예외 없이 처리된다")
    void normalizeChannel_nonNumericIds_doesNotThrow() {
        Map<String, Object> message1 = message("M1", "U1", "Alice", "text1",
                "2026-08-08T05:25:00.000000+00:00", 0);
        Map<String, Object> message2 = message("M2", "U2", "Bob", "text2",
                "2026-08-08T05:26:00.000000+00:00", 0);

        List<NormalizedEvent> events = normalizer.normalizeChannel(
                PROJECT_ID, GUILD_ID, channel("일반", "C1", false), List.of(message1, message2));

        assertThat(events).hasSize(2);
    }

    private Map<String, Object> reply(String id, String authorId, String authorName, String content,
                                       String parentMessageId, String timestamp) {
        Map<String, Object> message = message(id, authorId, authorName, content, timestamp, 19);
        message.put("message_reference", Map.of("message_id", parentMessageId));
        return message;
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
