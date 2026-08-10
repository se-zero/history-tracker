package com.history.pipeline_worker.source.googlechat;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GoogleChatNormalizer {

    private final RefsExtractor refsExtractor;

    public List<NormalizedEvent> normalizeMessages(
            String projectId,
            String spaceDisplayName,
            List<Map<String, Object>> messages,
            Map<String, GoogleChatRawService.PersonInfo> actorInfo
    ) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (messages == null) {
            return events;
        }
        for (Map<String, Object> message : messages) {
            events.add(normalizeMessage(projectId, spaceDisplayName, message, actorInfo));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private NormalizedEvent normalizeMessage(
            String projectId,
            String spaceDisplayName,
            Map<String, Object> message,
            Map<String, GoogleChatRawService.PersonInfo> actorInfo
    ) {
        // "spaces/{space}/messages/{message}" 리소스 이름 원문. 실측 확인(2026-08-08) — Message
        // 리소스에는 permalink 필드가 없다(Space에는 spaceUri가 있지만 메시지 단위 딥링크는 없음).
        // 클릭 가능한 형태로 조립할 근거가 없어, 검증되지 않은 URL을 지어내는 대신 결정적·고유한
        // 리소스 이름을 그대로 자연키로 쓴다.
        String name = (String) message.get("name");
        String text = (String) message.get("text");
        String createTime = (String) message.get("createTime");

        Map<String, Object> sender = (Map<String, Object>) message.get("sender");
        String senderResourceName = sender == null ? null : (String) sender.get("name");
        String senderId = resourceId(senderResourceName);

        Map<String, Object> thread = (Map<String, Object>) message.get("thread");
        String threadName = thread == null ? null : (String) thread.get("name");
        String conversationId = threadName != null && !threadName.isBlank() ? threadName : name;

        return new NormalizedEvent(
                projectId,
                "Communication",
                "GOOGLE_CHAT",
                parseTimestamp(createTime),
                actor(sender, senderResourceName, senderId, actorInfo),
                properties(name, text, spaceDisplayName, conversationId, createTime),
                refsExtractor.extract(text)
        );
    }

    private static Map<String, Object> properties(
            String name, String text, String spaceDisplayName, String conversationId, String createTime) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("url", name);
        properties.put("body", text);
        properties.put("channel", spaceDisplayName);
        properties.put("conversation_id", conversationId);
        properties.put("created_at", createTime);
        return properties;
    }

    /**
     * 사용자 인증으로는 {@code Message.sender}에 {@code displayName}이 오지 않는다(공식 문서·실측
     * 확인). 우선순위: ① 임베디드 {@code sender.displayName}이 어쩌다 채워져 있으면 그걸 쓴다(향후
     * API 변경에 대한 방어적 처리 — People API 호출 없이 끝나면 더 싸다) ② 없으면
     * {@code actorInfo}(People API 보강 결과)에서 채운다 ③ 그것도 없으면(조회 실패·프로필 비공개
     * 등) null — Discord와 같은 이유로 수동 병합 대상이 된다.
     */
    private static ActorDto actor(
            Map<String, Object> sender,
            String senderResourceName,
            String senderId,
            Map<String, GoogleChatRawService.PersonInfo> actorInfo
    ) {
        String embeddedName = sender == null ? null : (String) sender.get("displayName");
        GoogleChatRawService.PersonInfo resolved = senderResourceName == null ? null : actorInfo.get(senderResourceName);
        String senderName = embeddedName != null && !embeddedName.isBlank()
                ? embeddedName
                : (resolved == null ? null : resolved.name());
        String senderEmail = resolved == null ? null : resolved.email();
        return new ActorDto(senderId, senderName, senderEmail);
    }

    // "users/{id}" → "{id}"
    private static String resourceId(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        int slash = resourceName.lastIndexOf('/');
        return slash >= 0 ? resourceName.substring(slash + 1) : resourceName;
    }

    private static Instant parseTimestamp(String timestamp) {
        if (timestamp == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException exception) {
            return Instant.now();
        }
    }
}
