package com.history.pipeline_worker.source.slack;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
public class SlackRawService {

    public record UserInfo(String name, String email) {}
    public record SlackFetchContext(String auth, Map<String, UserInfo> userMap, SlackPacing pacing) {}
    public record SlackHistoryPage(Map<String, Object> channelData, String nextCursor) {}
    private record ChannelMessages(List<Object> messages, List<Object> threadCandidates, String nextCursor) {}

    // 페이지당 최대 수 (Slack API 최대값)
    private static final int PAGE_SIZE = 200;

    // 429 재시도 최대 횟수 (Discord와 동일 기준)
    private static final int MAX_RETRY_ON_RATE_LIMIT = 3;

    // history는 스레드 답글을 반환하지 않아, 커서 이전 루트에 새 답글이 달린 걸 잡으려면 루트가
    // 응답에 다시 보여야 한다. 커서로 그대로 자르면 기존 스레드의 새 답글이 전부 유실된다.
    // 14일보다 오래된 루트의 새 답글은 놓치는 알려진 한계다.
    private static final Duration THREAD_LOOKBACK = Duration.ofDays(14);

    // channel_join 등 실제 대화와 무관한 시스템 메시지 타입
    private static final List<String> NOISE_SUBTYPES = List.of(
            "channel_join", "channel_leave", "channel_archive",
            "channel_unarchive", "bot_message", "channel_purpose", "channel_name"
    );

    private final WebClient webClient;
    private final SlackRateLimiter rateLimiter;

    public SlackRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.slack.base-url}") String baseUrl,
            SlackRateLimiter rateLimiter
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.rateLimiter = rateLimiter;
    }

    public Map<String, Object> fetchSample(RawFetchRequest request) {
        SlackFetchContext context = prepareFetchContext(request);

        List<Object> allChannels = fetchChannels(context);
        if (allChannels.isEmpty()) {
            return Map.of(
                    "totalChannels", 0,
                    "totalUsers", context.userMap().size(),
                    "channels", List.of()
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> firstChannel = (Map<String, Object>) allChannels.get(0);
        SlackHistoryPage page = fetchHistoryPage(context, firstChannel, null, null);

        return Map.of(
                "totalChannels", allChannels.size(),
                "totalUsers", context.userMap().size(),
                "channels", List.of(page.channelData())
        );
    }

    // 실행마다 users.list를 다시 조회한다 — 프로세스 수명의 캐시는 연동 해제 후에도 구성원 이름·
    // 이메일이 힙에 남는다.
    public SlackFetchContext prepareFetchContext(RawFetchRequest request) {
        String auth = request.credentials();
        SlackPacing pacing = new SlackPacing();
        return new SlackFetchContext(auth, fetchUserMap(auth, pacing), pacing);
    }

    public List<Object> fetchChannels(SlackFetchContext context) {
        return fetchAllChannels(context.auth(), context.pacing());
    }

    public SlackHistoryPage fetchHistoryPage(SlackFetchContext context, Map<String, Object> channel,
                                              String cursor, Instant lastScannedAt) {
        String channelId = (String) channel.get("id");
        String channelName = (String) channel.get("name");

        ChannelMessages channelMessages = fetchMessagesPage(
                context.auth(),
                channelId,
                context.userMap(),
                lastScannedAt,
                cursor,
                context.pacing()
        );
        List<Object> messages = channelMessages.messages();
        List<Object> threads = fetchAllThreads(
                context.auth(),
                channelId,
                channelMessages.threadCandidates(),
                context.userMap(),
                lastScannedAt,
                context.pacing()
        );

        Map<String, Object> channelData = Map.of(
                "channelId", channelId,
                "channelName", channelName,
                "totalMessages", messages.size(),
                "messages", messages,
                "threads", threads
        );
        String nextCursor = channelMessages.nextCursor();
        return new SlackHistoryPage(channelData, nextCursor);
    }

    // users.list로 워크스페이스 전체 멤버의 userId → UserInfo(name, email) 맵 구성
    @SuppressWarnings("unchecked")
    private Map<String, UserInfo> fetchUserMap(String auth, SlackPacing pacing) {
        Map<String, UserInfo> userMap = new HashMap<>();
        String cursor = null;

        do {
            String uri = cursor == null
                    ? "/users.list?limit=" + PAGE_SIZE
                    : "/users.list?limit=" + PAGE_SIZE + "&cursor=" + cursor;

            Map<String, Object> response = requireOk(executeWithRateLimitRetry(
                    () -> webClient.get()
                            .uri(uri)
                            .header("Authorization", auth)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(),
                    SlackPacing.Endpoint.USERS_LIST, pacing), "users.list");

            rateLimiter.afterUsersList(pacing.minDelayMs(SlackPacing.Endpoint.USERS_LIST));

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
    private List<Object> fetchAllChannels(String auth, SlackPacing pacing) {
        List<Object> allChannels = new ArrayList<>();
        String cursor = null;

        do {
            String uri = cursor == null
                    ? "/conversations.list?types=public_channel,private_channel&limit=" + PAGE_SIZE
                    : "/conversations.list?types=public_channel,private_channel&limit=" + PAGE_SIZE + "&cursor=" + cursor;

            Map<String, Object> response = requireOk(executeWithRateLimitRetry(
                    () -> webClient.get()
                            .uri(uri)
                            .header("Authorization", auth)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(),
                    SlackPacing.Endpoint.CONVERSATIONS_LIST, pacing), "conversations.list");

            rateLimiter.afterConversationsList(pacing.minDelayMs(SlackPacing.Endpoint.CONVERSATIONS_LIST));

            List<Object> channels = (List<Object>) response.get("channels");
            if (channels != null) allChannels.addAll(channels);

            cursor = extractNextCursor(response);

        } while (cursor != null && !cursor.isBlank());

        return allChannels;
    }

    @SuppressWarnings("unchecked")
    private ChannelMessages fetchMessagesPage(String auth, String channelId,
                                              Map<String, UserInfo> userMap, Instant lastScannedAt,
                                              String cursor, SlackPacing pacing) {
        String uri = buildHistoryUri(cursor, lastScannedAt);

        Map<String, Object> response = requireOk(executeWithRateLimitRetry(
                () -> webClient.get()
                        .uri(uri, channelId)
                        .header("Authorization", auth)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(),
                SlackPacing.Endpoint.HISTORY, pacing), "conversations.history");

        rateLimiter.afterConversationsHistory(pacing.minDelayMs(SlackPacing.Endpoint.HISTORY));

        List<Object> pageMessages = new ArrayList<>();
        List<Object> threadCandidates = new ArrayList<>();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
        // 신규 한도 체제(비마켓플레이스 앱)는 limit 값과 무관하게 페이지당 15건으로 잘린다 — 어느 체제인지 판별할 때 켠다
        log.debug("history 페이지 수신: channel={}, raw={}건", channelId, messages == null ? 0 : messages.size());
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
                                          Instant lastScannedAt, SlackPacing pacing) {
        List<Object> allThreads = new ArrayList<>();

        for (Object msg : threadCandidates) {
            Map<String, Object> message = (Map<String, Object>) msg;
            Object replyCount = message.get("reply_count");
            if (replyCount == null || ((Number) replyCount).intValue() == 0) continue;

            String ts = (String) message.get("ts");
            List<Object> replies = fetchAllReplies(auth, channelId, ts, userMap, lastScannedAt, pacing);
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
                                          Instant lastScannedAt, SlackPacing pacing) {
        List<Object> allReplies = new ArrayList<>();
        String cursor = null;

        do {
            String uri = buildRepliesUri(cursor, lastScannedAt);

            Map<String, Object> response = requireOk(executeWithRateLimitRetry(
                    () -> webClient.get()
                            .uri(uri, channelId, threadTs)
                            .header("Authorization", auth)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(),
                    SlackPacing.Endpoint.REPLIES, pacing), "conversations.replies");

            rateLimiter.afterConversationsReplies(pacing.minDelayMs(SlackPacing.Endpoint.REPLIES));

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

    private String buildHistoryUri(String cursor, Instant lastScannedAt) {
        String uri = "/conversations.history?channel={channelId}&limit=" + PAGE_SIZE;
        if (lastScannedAt != null) {
            uri += "&oldest=" + instantToSlackTs(lastScannedAt.minus(THREAD_LOOKBACK));
        }
        if (cursor != null) {
            uri += "&cursor=" + cursor;
        }
        return uri;
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

    // 429면 Retry-After 헤더만큼 대기 후 재시도. endpoint의 pacing을 승격해 이후 같은 endpoint 호출도 그만큼 늦춘다.
    private <T> T executeWithRateLimitRetry(Supplier<T> request, SlackPacing.Endpoint endpoint, SlackPacing pacing) {
        int attempts = 0;
        while (true) {
            try {
                return request.get();
            } catch (WebClientResponseException.TooManyRequests exception) {
                attempts++;
                if (attempts > MAX_RETRY_ON_RATE_LIMIT) {
                    throw exception;
                }
                long retryAfterSeconds = parseRetryAfterSeconds(exception.getHeaders().getFirst("Retry-After"));
                pacing.escalate(endpoint, retryAfterSeconds * 1000);
                log.warn("Slack rate limit(429) — endpoint={}, {}초 대기 후 재시도 ({}/{})",
                        endpoint, retryAfterSeconds, attempts, MAX_RETRY_ON_RATE_LIMIT);
                rateLimiter.awaitRetry(retryAfterSeconds);
            }
        }
    }

    // Retry-After는 정수 초 문자열. 헤더가 없거나 형식이 어긋나면 신규 한도 체제에서 실측한 60초로
    // 보수적 폴백한다(짧게 잡으면 다시 429를 유발할 수 있어 안전한 쪽으로 붙인다).
    static long parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null) {
            return 60L;
        }
        try {
            long seconds = Long.parseLong(headerValue);
            return seconds >= 0 ? seconds : 60L;
        } catch (NumberFormatException e) {
            return 60L;
        }
    }

    // ok:false나 null 응답을 조용히 빈 페이지로 넘기면 "가짜 채널 완료"로 이어져 남은 메시지가
    // 영구 누락된다 — 즉시 실패시켜 재수집 대상임을 드러낸다.
    private Map<String, Object> requireOk(Map<String, Object> response, String api) {
        if (response == null) {
            throw new IllegalStateException("Slack API error: null response (" + api + ")");
        }
        if (!Boolean.TRUE.equals(response.get("ok"))) {
            throw new IllegalStateException("Slack API error: " + response.get("error") + " (" + api + ")");
        }
        return response;
    }
}
