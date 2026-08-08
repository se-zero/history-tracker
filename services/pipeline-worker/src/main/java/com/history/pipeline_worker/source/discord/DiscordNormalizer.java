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

    public List<NormalizedEvent> normalizeChannel(
            String projectId,
            String guildId,
            Map<String, Object> channel,
            List<Map<String, Object>> messages
    ) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (messages == null) {
            return events;
        }
        for (Map<String, Object> message : messages) {
            events.add(normalizeMessage(projectId, guildId, channel, message));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private NormalizedEvent normalizeMessage(
            String projectId,
            String guildId,
            Map<String, Object> channel,
            Map<String, Object> message
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
        properties.put("conversation_id", conversationId(message, channel, messageId));
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
    private static String conversationId(Map<String, Object> message, Map<String, Object> channel, String messageId) {
        // 스레드 안 메시지는 전부 그 스레드(채널) id로 묶는다 — 루트든 답글이든 같은 대화다
        if (Boolean.TRUE.equals(channel.get("isThread"))) {
            return (String) channel.get("id");
        }
        Object type = message.get("type");
        if (type instanceof Number number && number.intValue() == TYPE_REPLY) {
            Map<String, Object> reference = (Map<String, Object>) message.get("message_reference");
            if (reference != null && reference.get("message_id") != null) {
                return (String) reference.get("message_id");
            }
        }
        return messageId;
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
