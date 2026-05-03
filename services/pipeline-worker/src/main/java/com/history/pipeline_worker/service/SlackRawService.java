package com.history.pipeline_worker.service;

import com.history.pipeline_worker.checkpoint.FileCheckpointManager;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.ratelimit.SlackRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SlackRawService {

    public record UserInfo(String name, String email) {}
    public record SlackFetchContext(String auth, Instant lastScannedAt, Map<String, UserInfo> userMap) {}
    public record SlackHistoryPage(Map<String, Object> channelData, String nextCursor, boolean finished) {}
    private record ChannelMessages(List<Object> messages, List<Object> threadCandidates, String nextCursor) {}

    // 페이지당 최대 수 (Slack API 최대값)
    private static final int PAGE_SIZE = 200;

    // channel_join 등 실제 대화와 무관한 시스템 메시지 타입
    private static final List<String> NOISE_SUBTYPES = List.of(
            "channel_join", "channel_leave", "channel_archive",
            "channel_unarchive", "bot_message", "channel_purpose", "channel_name"
    );

    private final WebClient webClient;
    private final SlackRateLimiter rateLimiter;
    private final FileCheckpointManager checkpointManager;

    public SlackRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.slack.base-url}") String baseUrl,
            SlackRateLimiter rateLimiter,
            FileCheckpointManager checkpointManager
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.rateLimiter = rateLimiter;
        this.checkpointManager = checkpointManager;
    }

    public Map<String, Object> fetch(RawFetchRequest request) {
        SlackFetchContext context = prepareFetchContext(request);

        // 전체 채널 목록 수집
        List<Object> allChannels = fetchChannels(context);

        // 각 채널의 메시지 + 스레드 수집
        List<Object> channelData = new ArrayList<>();
        for (Object ch : allChannels) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) ch;
            channelData.add(fetchChannel(channel, context));
        }

        log.info("Slack 수집 완료: {} 채널", channelData.size());

        return Map.of(
                "totalChannels", allChannels.size(),
                "totalUsers", context.userMap().size(),
                "channels", channelData
        );
    }

    public SlackFetchContext prepareFetchContext(RawFetchRequest request) {
        String auth = request.credentials();
        Instant lastScannedAt = checkpointManager.getCached().slack.lastScannedAt;
        return new SlackFetchContext(auth, lastScannedAt, fetchUserMap(auth));
    }

    public List<Object> fetchChannels(SlackFetchContext context) {
        return fetchAllChannels(context.auth());
    }

    public SlackHistoryPage fetchHistoryPage(SlackFetchContext context, Map<String, Object> channel, String cursor) {
        String channelId = (String) channel.get("id");
        String channelName = (String) channel.get("name");

        ChannelMessages channelMessages = fetchMessagesPage(
                context.auth(),
                channelId,
                context.userMap(),
                context.lastScannedAt(),
                cursor
        );
        List<Object> messages = channelMessages.messages();
        List<Object> threads = fetchAllThreads(
                context.auth(),
                channelId,
                channelMessages.threadCandidates(),
                context.userMap(),
                context.lastScannedAt()
        );

        Map<String, Object> channelData = Map.of(
                "channelId", channelId,
                "channelName", channelName,
                "totalMessages", messages.size(),
                "messages", messages,
                "threads", threads
        );
        String nextCursor = channelMessages.nextCursor();
        return new SlackHistoryPage(channelData, nextCursor, nextCursor == null || nextCursor.isBlank());
    }

    // users.list로 워크스페이스 전체 멤버의 userId → UserInfo(name, email) 맵 구성
    @SuppressWarnings("unchecked")
    private Map<String, UserInfo> fetchUserMap(String auth) {
        Map<String, UserInfo> userMap = new HashMap<>();
        String cursor = null;

        do {
            String uri = cursor == null
                    ? "/users.list?limit=" + PAGE_SIZE
                    : "/users.list?limit=" + PAGE_SIZE + "&cursor=" + cursor;

            Map<String, Object> response = webClient.get()
                    .uri(uri)
                    .header("Authorization", auth)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            rateLimiter.afterUsersList();

            if (response == null) break;

            List<Map<String, Object>> members = (List<Map<String, Object>>) response.get("members");
            if (members != null) {
                for (Map<String, Object> member : members) {
                    String id = (String) member.get("id");
                    Map<String, Object> profile = (Map<String, Object>) member.get("profile");
                    String displayName = null;
                    String email = null;
                    if (profile != null) {
                        String dn = (String) profile.get("display_name");
                        displayName = (dn != null && !dn.isBlank())
                                ? dn
                                : (String) profile.get("real_name");
                        email = (String) profile.get("email");
                    }
                    if (displayName == null || displayName.isBlank()) {
                        displayName = (String) member.get("name");
                    }
                    if (id != null) userMap.put(id, new UserInfo(displayName, email));
                }
            }

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return userMap;
    }

    // xoxp로 접근 가능한 전체 채널 목록 수집 (Tier 2: conversations.list)
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

            rateLimiter.afterConversationsList();

            if (response == null) break;

            List<Object> channels = (List<Object>) response.get("channels");
            if (channels != null) allChannels.addAll(channels);

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return allChannels;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchChannel(Map<String, Object> channel, SlackFetchContext context) {
        String cursor = null;
        List<Object> messages = new ArrayList<>();
        List<Object> threads = new ArrayList<>();
        Map<String, Object> lastPage = null;

        do {
            SlackHistoryPage page = fetchHistoryPage(context, channel, cursor);
            lastPage = page.channelData();
            messages.addAll((List<Object>) lastPage.get("messages"));
            threads.addAll((List<Object>) lastPage.get("threads"));
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());

        String channelId = (String) channel.get("id");
        String channelName = (String) channel.get("name");
        if (lastPage != null) {
            channelId = (String) lastPage.get("channelId");
            channelName = (String) lastPage.get("channelName");
        }

        return Map.of(
                "channelId", channelId,
                "channelName", channelName,
                "totalMessages", messages.size(),
                "messages", messages,
                "threads", threads
        );
    }

    @SuppressWarnings("unchecked")
    private ChannelMessages fetchMessagesPage(String auth, String channelId,
                                              Map<String, UserInfo> userMap, Instant lastScannedAt,
                                              String cursor) {
        String uri = cursor == null
                ? "/conversations.history?channel={channelId}&limit=" + PAGE_SIZE
                : "/conversations.history?channel={channelId}&limit=" + PAGE_SIZE + "&cursor=" + cursor;

        Map<String, Object> response = webClient.get()
                .uri(uri, channelId)
                .header("Authorization", auth)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        rateLimiter.afterConversationsHistory();

        if (response == null) {
            return new ChannelMessages(List.of(), List.of(), null);
        }

        List<Object> pageMessages = new ArrayList<>();
        List<Object> threadCandidates = new ArrayList<>();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                if (isNoise(msg)) continue;

                Map<String, Object> enriched = enrichWithUserName(msg, userMap);
                if (shouldFetchThreadReplies(msg, lastScannedAt)) {
                    threadCandidates.add(enriched);
                }
                if (!isBeforeCheckpoint(msg, lastScannedAt)) {
                    pageMessages.add(enriched);
                }
            }
        }

        return new ChannelMessages(pageMessages, threadCandidates, extractNextCursor(response));
    }

    // 스레드가 달린 메시지의 replies를 모두 수집
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllThreads(String auth, String channelId,
                                          List<Object> threadCandidates, Map<String, UserInfo> userMap,
                                          Instant lastScannedAt) {
        List<Object> allThreads = new ArrayList<>();

        for (Object msg : threadCandidates) {
            Map<String, Object> message = (Map<String, Object>) msg;
            Object replyCount = message.get("reply_count");
            if (replyCount == null || ((Number) replyCount).intValue() == 0) continue;

            String ts = (String) message.get("ts");
            List<Object> replies = fetchAllReplies(auth, channelId, ts, userMap, lastScannedAt);
            if (!replies.isEmpty()) {
                allThreads.add(Map.of("thread_ts", ts, "replies", replies));
            }
        }

        return allThreads;
    }

    // 스레드 replies도 cursor 페이지네이션으로 전체 수집 (Tier 3: conversations.replies)
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllReplies(String auth, String channelId,
                                          String threadTs, Map<String, UserInfo> userMap,
                                          Instant lastScannedAt) {
        List<Object> allReplies = new ArrayList<>();
        String cursor = null;

        do {
            String uri = buildRepliesUri(cursor, lastScannedAt);

            Map<String, Object> response = webClient.get()
                    .uri(uri, channelId, threadTs)
                    .header("Authorization", auth)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            rateLimiter.afterConversationsReplies();

            if (response == null) break;

            List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
            if (messages != null) {
                for (Map<String, Object> reply : messages) {
                    String replyTs = (String) reply.get("ts");
                    if (threadTs.equals(replyTs)) continue;
                    if (isNoise(reply)) continue;
                    if (isBeforeCheckpoint(reply, lastScannedAt)) continue;
                    allReplies.add(enrichWithUserName(reply, userMap));
                }
            }

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return allReplies;
    }

    private String buildRepliesUri(String cursor, Instant lastScannedAt) {
        String uri = "/conversations.replies?channel={channelId}&ts={ts}&limit=" + PAGE_SIZE;
        if (lastScannedAt != null) {
            uri += "&oldest=" + instantToSlackTs(lastScannedAt);
        }
        if (cursor != null) {
            uri += "&cursor=" + cursor;
        }
        return uri;
    }

    private boolean shouldFetchThreadReplies(Map<String, Object> message, Instant lastScannedAt) {
        Object replyCount = message.get("reply_count");
        if (!(replyCount instanceof Number) || ((Number) replyCount).intValue() == 0) {
            return false;
        }
        if (lastScannedAt == null || !isBeforeCheckpoint(message, lastScannedAt)) {
            return true;
        }

        String latestReply = (String) message.get("latest_reply");
        if (latestReply == null) {
            return false;
        }
        try {
            return tsToInstant(latestReply).isAfter(lastScannedAt);
        } catch (Exception e) {
            return false;
        }
    }

    /** ts가 lastScannedAt 이전이면 true (스킵 대상) */
    private boolean isBeforeCheckpoint(Map<String, Object> message, Instant lastScannedAt) {
        if (lastScannedAt == null) return false;
        String ts = (String) message.get("ts");
        if (ts == null) return false;
        try {
            return !tsToInstant(ts).isAfter(lastScannedAt);
        } catch (Exception e) {
            return false;
        }
    }

    /** Slack ts("1706000000.123456") → Instant */
    private Instant tsToInstant(String ts) {
        String[] parts = ts.split("\\.");
        long seconds = Long.parseLong(parts[0]);
        long nanos = 0;
        if (parts.length > 1) {
            String fraction = parts[1];
            // 나노초 9자리에 맞게 패딩/절삭
            while (fraction.length() < 9) fraction += "0";
            nanos = Long.parseLong(fraction.substring(0, 9));
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private String instantToSlackTs(Instant instant) {
        return "%d.%06d".formatted(instant.getEpochSecond(), instant.getNano() / 1_000);
    }

    // channel_join 등 대화와 무관한 시스템 메시지 여부 확인
    private boolean isNoise(Map<String, Object> message) {
        String subtype = (String) message.get("subtype");
        return subtype != null && NOISE_SUBTYPES.contains(subtype);
    }

    // userId → userName·userEmail을 메시지에 추가
    private Map<String, Object> enrichWithUserName(Map<String, Object> message, Map<String, UserInfo> userMap) {
        String userId = (String) message.get("user");
        if (userId == null || !userMap.containsKey(userId)) return message;

        UserInfo info = userMap.get(userId);
        Map<String, Object> enriched = new HashMap<>(message);
        enriched.put("userName", info.name());
        if (info.email() != null) enriched.put("userEmail", info.email());
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private String extractNextCursor(Map<String, Object> response) {
        Map<String, Object> meta = (Map<String, Object>) response.get("response_metadata");
        if (meta == null) return null;
        return (String) meta.get("next_cursor");
    }
}
