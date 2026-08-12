package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DiscordNormalizer {

    // Discord 답글 메시지 타입
    private static final int TYPE_REPLY = 19;

    // 실제 멘션 기능으로 넣은 <@id> / <@!id> — 자동완성 없이 그냥 타이핑한 "@이름"은 이 패턴에 안 걸려
    // 그대로 남는다(실측 확인 — docs/discord-integration.md 「확인 완료」).
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");

    private final RefsExtractor refsExtractor;

    // 단발 호출용 — 이 호출 하나로 닫힌 맵을 새로 만든다. 답글의 답글(A←B←C)이 이 호출 안에
    // 전부 들어 있으면 그대로 한 대화로 접히지만, 페이지를 가로지르는 체인은 여기서 해소되지
    // 않는다(그러려면 호출 사이에 맵이 살아 있어야 한다 — 아래 5-인자 버전 참고).
    public List<NormalizedEvent> normalizeChannel(
            String projectId,
            String guildId,
            Map<String, Object> channel,
            List<Map<String, Object>> messages
    ) {
        return normalizeChannel(projectId, guildId, channel, messages, new HashMap<>());
    }

    /**
     * {@code resolvedConversationIds}는 messageId → 해소된 conversation_id 맵이다. Discord 답글은
     * message_reference로 <b>직접 부모</b>만 가리켜, A←B←C 체인을 그대로 두면 B가 A로 묶인 뒤 C는
     * B로만 묶여 대화가 둘로 쪼개진다(Slack은 항상 스레드 루트를 가리켜 이 문제가 없다). 이 맵을
     * 채널 하나의 수집 실행 동안(페이지를 가로질러) 유지하면서 자식이 부모의 <b>해소된</b> 값을
     * 물려받게 하면 체인 전체가 한 conversation_id로 접힌다. 부모가 이 맵에 없으면(다른 실행에서
     * 수집됐거나 노이즈로 필터된 메시지) 직접 부모 id로 폴백한다 — 그 잔여는 기존 동작과 같다.
     */
    public List<NormalizedEvent> normalizeChannel(
            String projectId,
            String guildId,
            Map<String, Object> channel,
            List<Map<String, Object>> messages,
            Map<String, String> resolvedConversationIds
    ) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (messages == null) {
            return events;
        }
        // 부모를 자식보다 먼저 해소해야 하는데, 한 페이지 안에서 Discord 응답은 최신→과거
        // 내림차순이다(배치 안쪽 정렬 — docs/discord-integration.md 「확인 완료」 4). id는 snowflake라
        // 오름차순=생성 순 오름차순이므로 처리 순서만 id로 정렬한다 — 반환하는 이벤트 목록 자체의
        // 순서는 발행·checkpoint 어느 쪽도 기대지 않는다.
        List<Map<String, Object>> ordered = new ArrayList<>(messages);
        ordered.sort(Comparator.comparingLong(m -> parseSnowflake((String) m.get("id"))));
        for (Map<String, Object> message : ordered) {
            events.add(normalizeMessage(projectId, guildId, channel, message, resolvedConversationIds));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private NormalizedEvent normalizeMessage(
            String projectId,
            String guildId,
            Map<String, Object> channel,
            Map<String, Object> message,
            Map<String, String> resolvedConversationIds
    ) {
        String channelId = (String) channel.get("id");
        String channelName = (String) channel.get("name");
        String messageId = (String) message.get("id");
        String timestamp = (String) message.get("timestamp");
        String content = substituteMentions((String) message.get("content"), message);

        Map<String, Object> author = (Map<String, Object>) message.get("author");
        String authorId = author == null ? null : (String) author.get("id");
        String authorName = displayName(author);

        Map<String, Object> properties = new HashMap<>();
        properties.put("url", "https://discord.com/channels/" + guildId + "/" + channelId + "/" + messageId);
        properties.put("body", content);
        properties.put("channel", channelName);
        properties.put("conversation_id", conversationId(message, channel, messageId, resolvedConversationIds));
        properties.put("created_at", timestamp);

        return new NormalizedEvent(
                projectId,
                "Communication",
                "DISCORD",
                parseTimestamp(timestamp),
                // 봇은 타인의 이메일을 얻을 수 없다 — 동일인 판단은 이름에만 의존(§7)
                new ActorDto(authorId, authorName, null),
                properties,
                refsExtractor.extract(content)
        );
    }

    @SuppressWarnings("unchecked")
    private static String conversationId(
            Map<String, Object> message,
            Map<String, Object> channel,
            String messageId,
            Map<String, String> resolvedConversationIds
    ) {
        String resolved;
        // 스레드 안 메시지는 전부 그 스레드(채널) id로 묶는다 — 루트든 답글이든 같은 대화다
        if (Boolean.TRUE.equals(channel.get("isThread"))) {
            resolved = (String) channel.get("id");
        } else {
            Object type = message.get("type");
            if (type instanceof Number number && number.intValue() == TYPE_REPLY) {
                Map<String, Object> reference = (Map<String, Object>) message.get("message_reference");
                String parentId = reference == null ? null : (String) reference.get("message_id");
                // 부모가 이미 해소돼 있으면 그 값을 물려받아 체인 전체를 한 대화로 접는다.
                // 못 찾으면(다른 실행에서 수집·필터된 메시지) 직접 부모 id로 폴백한다.
                resolved = parentId == null ? messageId : resolvedConversationIds.getOrDefault(parentId, parentId);
            } else {
                resolved = messageId;
            }
        }
        resolvedConversationIds.put(messageId, resolved);
        return resolved;
    }

    // 실제 Discord id는 항상 숫자 snowflake다 — 파싱 실패는 정렬 순서를 보장하지 않을 뿐(원본 순서로
    // 남는다) 예외로 이어지지 않는다. DiscordRawService.advances()와 같은 방어적 파싱이다.
    private static long parseSnowflake(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static String substituteMentions(String content, Map<String, Object> message) {
        if (content == null || content.isBlank()) {
            return content;
        }
        List<Map<String, Object>> mentions = (List<Map<String, Object>>) message.get("mentions");
        if (mentions == null || mentions.isEmpty()) {
            return content;
        }

        Map<String, String> idToName = new HashMap<>();
        for (Map<String, Object> mention : mentions) {
            String id = (String) mention.get("id");
            if (id != null) {
                idToName.put(id, displayName(mention));
            }
        }

        Matcher matcher = USER_MENTION.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = idToName.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(name != null ? "@" + name : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // global_name(표시 이름) 우선, 없으면 username
    private static String displayName(Map<String, Object> user) {
        if (user == null) {
            return null;
        }
        Object globalName = user.get("global_name");
        if (globalName instanceof String name && !name.isBlank()) {
            return name;
        }
        Object username = user.get("username");
        return username instanceof String name ? name : null;
    }

    private static Instant parseTimestamp(String timestamp) {
        if (timestamp == null) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException exception) {
            return Instant.now();
        }
    }
}
