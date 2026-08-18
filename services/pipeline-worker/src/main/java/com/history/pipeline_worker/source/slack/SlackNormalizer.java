package com.history.pipeline_worker.source.slack;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SlackNormalizer {

    private final RefsExtractor refsExtractor;

    // Slack fetch 결과의 channels 배열 → Communication 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeChannels(String projectId, Map<String, Object> slackData) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (slackData == null) return events;

        List<Map<String, Object>> channels = (List<Map<String, Object>>) slackData.get("channels");
        if (channels == null) return events;

        for (Map<String, Object> channel : channels) {
            events.addAll(normalizeChannel(projectId, channel));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeChannel(String projectId, Map<String, Object> channel) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (channel == null) return events;

        String channelName = (String) channel.get("channelName");
        String channelId = (String) channel.get("channelId");

        List<Map<String, Object>> messages = (List<Map<String, Object>>) channel.get("messages");
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                events.add(normalizeMessage(projectId, msg, channelName, channelId));
            }
        }

        // 스레드 replies도 각각 Communication 이벤트로 변환
        List<Map<String, Object>> threads = (List<Map<String, Object>>) channel.get("threads");
        if (threads != null) {
            for (Map<String, Object> thread : threads) {
                String threadTs = (String) thread.get("thread_ts");
                List<Map<String, Object>> replies = (List<Map<String, Object>>) thread.get("replies");
                if (replies != null) {
                    for (Map<String, Object> reply : replies) {
                        events.add(normalizeMessage(projectId, reply, channelName, channelId, threadTs));
                    }
                }
            }
        }

        return events;
    }

    private NormalizedEvent normalizeMessage(String projectId, Map<String, Object> msg, String channelName, String channelId) {
        return normalizeMessage(projectId, msg, channelName, channelId, null);
    }

    private NormalizedEvent normalizeMessage(String projectId, Map<String, Object> msg, String channelName, String channelId, String threadTs) {
        String userId    = (String) msg.get("user");
        String userName  = (String) msg.get("userName");
        String userEmail = (String) msg.get("userEmail");
        String text = (String) msg.get("text");
        String ts = (String) msg.get("ts");

        Map<String, Object> properties = new HashMap<>();
        properties.put("body", text);
        properties.put("channel", channelName);
        properties.put("url", "https://slack.com/archives/" + channelId + "/p" + tsToMessageId(ts));
        // 루트 메시지는 자신의 ts, 스레드 reply는 부모 메시지의 ts
        properties.put("conversation_id", threadTs != null ? threadTs : ts);
        properties.put("created_at", null);

        return new NormalizedEvent(
                projectId,
                "Communication",
                "SLACK",
                tsToInstant(ts),
                new ActorDto(userId, userName, userEmail),
                properties,
                refsExtractor.extract(text)
        );
    }

    private String tsToMessageId(String ts) {
        if (ts == null) return "";
        return ts.replace(".", "");
    }

    // Slack ts는 Unix epoch 소수점 문자열 (예: "1773799131.363769")
    private Instant tsToInstant(String ts) {
        if (ts == null) return Instant.now();
        try {
            BigDecimal bd = new BigDecimal(ts);
            long seconds = bd.longValue();
            long nanos = bd.remainder(BigDecimal.ONE)
                    .movePointRight(9).longValue();
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
