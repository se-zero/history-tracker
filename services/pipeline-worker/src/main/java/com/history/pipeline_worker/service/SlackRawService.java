package com.history.pipeline_worker.service;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SlackRawService {

    // 페이지당 최대 수 (Slack API 최대값)
    private static final int PAGE_SIZE = 200;

    private final WebClient webClient;

    public SlackRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.slack.base-url}") String baseUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public Map<String, Object> fetch(RawFetchRequest request) {
        String auth = request.credentials();

        // 봇이 참여 중인 전체 채널 목록 수집
        List<Object> allChannels = fetchAllChannels(auth);

        // 각 채널의 메시지 + 스레드 수집
        List<Object> channelData = new ArrayList<>();
        for (Object ch : allChannels) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) ch;
            String channelId = (String) channel.get("id");
            String channelName = (String) channel.get("name");

            List<Object> messages = fetchAllMessages(auth, channelId);
            List<Object> threads = fetchAllThreads(auth, channelId, messages);

            channelData.add(Map.of(
                    "channelId", channelId,
                    "channelName", channelName,
                    "totalMessages", messages.size(),
                    "messages", messages,
                    "threads", threads
            ));
        }

        return Map.of(
                "totalChannels", allChannels.size(),
                "channels", channelData
        );
    }

    // 봇이 속한 전체 채널 목록을 cursor 페이지네이션으로 수집
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllChannels(String auth) {
        List<Object> allChannels = new ArrayList<>();
        String cursor = null;

        do {
            String uri = cursor == null
                    ? "/conversations.list?types=public_channel,private_channel&limit=" + PAGE_SIZE
                    : "/conversations.list?types=public_channel,private_channel&limit=" + PAGE_SIZE + "&cursor=" + cursor;

            Map<String, Object> response = webClient.get()
                    .uri(uri)
                    .header("Authorization", auth)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) break;

            List<Object> channels = (List<Object>) response.get("channels");
            if (channels != null) {
                allChannels.addAll(channels);
            }

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return allChannels;
    }

    // cursor 기반 페이지네이션으로 채널 전체 메시지 수집
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllMessages(String auth, String channelId) {
        List<Object> allMessages = new ArrayList<>();
        String cursor = null;

        do {
            String uri = cursor == null
                    ? "/conversations.history?channel={channelId}&limit=" + PAGE_SIZE
                    : "/conversations.history?channel={channelId}&limit=" + PAGE_SIZE + "&cursor=" + cursor;

            Map<String, Object> response = webClient.get()
                    .uri(uri, channelId)
                    .header("Authorization", auth)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) break;

            List<Object> messages = (List<Object>) response.get("messages");
            if (messages != null) {
                allMessages.addAll(messages);
            }

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return allMessages;
    }

    // 스레드가 달린 메시지의 replies를 모두 수집
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllThreads(String auth, String channelId, List<Object> messages) {
        List<Object> allThreads = new ArrayList<>();

        for (Object msg : messages) {
            Map<String, Object> message = (Map<String, Object>) msg;
            Object replyCount = message.get("reply_count");
            if (replyCount == null || ((Number) replyCount).intValue() == 0) continue;

            String ts = (String) message.get("ts");
            List<Object> replies = fetchAllReplies(auth, channelId, ts);
            if (!replies.isEmpty()) {
                allThreads.add(Map.of("thread_ts", ts, "replies", replies));
            }
        }

        return allThreads;
    }

    // 스레드 replies도 cursor 페이지네이션으로 전체 수집
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllReplies(String auth, String channelId, String threadTs) {
        List<Object> allReplies = new ArrayList<>();
        String cursor = null;

        do {
            String uri = cursor == null
                    ? "/conversations.replies?channel={channelId}&ts={ts}&limit=" + PAGE_SIZE
                    : "/conversations.replies?channel={channelId}&ts={ts}&limit=" + PAGE_SIZE + "&cursor=" + cursor;

            Map<String, Object> response = webClient.get()
                    .uri(uri, channelId, threadTs)
                    .header("Authorization", auth)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) break;

            List<Object> messages = (List<Object>) response.get("messages");
            if (messages != null) {
                // 첫 번째 메시지는 원본 메시지(이미 수집됨)이므로 제외
                allReplies.addAll(messages.subList(cursor == null ? 1 : 0, messages.size()));
            }

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return allReplies;
    }

    @SuppressWarnings("unchecked")
    private String extractNextCursor(Map<String, Object> response) {
        Map<String, Object> meta = (Map<String, Object>) response.get("response_metadata");
        if (meta == null) return null;
        return (String) meta.get("next_cursor");
    }
}
