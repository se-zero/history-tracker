package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
public class DiscordRawService {

    public record DiscordFetchContext(String auth, String guildId, Instant lastScannedAt) {}

    // Discord API 페이지당 최대 수
    private static final int PAGE_SIZE = 100;

    // Discord epoch(2015-01-01T00:00:00Z) — snowflake ID의 타임스탬프 기준
    private static final long DISCORD_EPOCH_MS = 1420070400000L;

    // 정규화 대상 메시지 타입 — 0(일반), 19(답글). 그 외(시스템 이벤트)는 여기서 걸러 Normalizer를 단순하게 둔다.
    private static final Set<Integer> ALLOWED_MESSAGE_TYPES = Set.of(0, 19);

    // 429 재시도 상한 — 무한 대기를 막는다
    private static final int MAX_RETRY_ON_RATE_LIMIT = 3;

    private final WebClient webClient;
    private final DiscordRateLimiter rateLimiter;

    public DiscordRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.discord.api-base-url}") String baseUrl,
            DiscordRateLimiter rateLimiter
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.rateLimiter = rateLimiter;
    }

    public DiscordFetchContext prepareFetchContext(RawFetchRequest request, Instant lastScannedAt) {
        return new DiscordFetchContext(request.credentials(), request.projectKey(), lastScannedAt);
    }

    /**
     * 길드의 텍스트 채널(type 0·5)과 활성 스레드를 한 목록으로 합쳐 반환한다. 스레드도 채널이라
     * 메시지 조회 방식이 같다 — 항목마다 {@code isThread}로 구분해 Normalizer가 conversation_id를
     * 정하는 데 쓴다.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchChannels(DiscordFetchContext context) {
        List<Map<String, Object>> channels = new ArrayList<>();

        List<Map<String, Object>> guildChannels = executeWithRateLimitRetry(() -> webClient.get()
                .uri("/guilds/{guildId}/channels", context.guildId())
                .header("Authorization", context.auth())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block());
        if (guildChannels != null) {
            for (Map<String, Object> channel : guildChannels) {
                if (isTextChannelType(channel.get("type"))) {
                    channels.add(toChannelEntry(channel, false));
                }
            }
        }

        Map<String, Object> activeThreadsResponse = executeWithRateLimitRetry(() -> webClient.get()
                .uri("/guilds/{guildId}/threads/active", context.guildId())
                .header("Authorization", context.auth())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block());
        if (activeThreadsResponse != null) {
            List<Map<String, Object>> threads = (List<Map<String, Object>>) activeThreadsResponse.get("threads");
            if (threads != null) {
                for (Map<String, Object> thread : threads) {
                    channels.add(toChannelEntry(thread, true));
                }
            }
        }

        return channels;
    }

    /**
     * 체크포인트 이후 새 메시지 전체를 수집한다. {@code after}는 항상 최신부터 내림차순으로 채우므로
     * (실측 확정 — docs/discord-integration.md 「확인 완료」), 체크포인트 이후 메시지가 페이지 크기(100)를
     * 넘으면 1회 호출로 못 받는다. 가득 찬 페이지를 받으면 그 배치의 가장 오래된 id로 {@code before}로
     * 바꿔 체크포인트에 닿을 때까지 내려간다 — {@code before}·{@code after}·{@code around}가 상호
     * 배타적이라 한 호출에 섞을 수 없다.
     */
    public List<Map<String, Object>> fetchChannelMessages(DiscordFetchContext context, Map<String, Object> channel) {
        String channelId = (String) channel.get("id");
        Instant checkpoint = context.lastScannedAt();

        List<Map<String, Object>> collected = new ArrayList<>();
        List<Map<String, Object>> page = fetchMessagesPage(context.auth(), channelId, "after", afterCursor(checkpoint));
        collected.addAll(page);

        while (page.size() == PAGE_SIZE) {
            String oldestId = lastMessageId(page);
            if (checkpoint != null && !snowflakeToInstant(oldestId).isAfter(checkpoint)) {
                break;
            }
            page = fetchMessagesPage(context.auth(), channelId, "before", oldestId);
            collected.addAll(filterAfterCheckpoint(page, checkpoint));
        }

        return filterNoise(collected);
    }

    private List<Map<String, Object>> fetchMessagesPage(String auth, String channelId, String cursorParam, String cursorValue) {
        List<Map<String, Object>> page = executeWithRateLimitRetry(() -> webClient.get()
                .uri("/channels/{channelId}/messages?limit=" + PAGE_SIZE + "&" + cursorParam + "={cursorValue}",
                        channelId, cursorValue)
                .header("Authorization", auth)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block());
        return page == null ? List.of() : page;
    }

    private static String afterCursor(Instant checkpoint) {
        // 체크포인트가 없으면(초기 수집) Discord epoch 시작(snowflake "0")부터 — 전체 히스토리 대상
        return checkpoint == null ? "0" : instantToSnowflake(checkpoint);
    }

    private static boolean isTextChannelType(Object type) {
        // 0=GUILD_TEXT, 5=GUILD_ANNOUNCEMENT
        return type instanceof Number number && (number.intValue() == 0 || number.intValue() == 5);
    }

    private static Map<String, Object> toChannelEntry(Map<String, Object> raw, boolean isThread) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", raw.get("id"));
        entry.put("name", raw.get("name"));
        entry.put("isThread", isThread);
        return entry;
    }

    private static String lastMessageId(List<Map<String, Object>> page) {
        return (String) page.get(page.size() - 1).get("id");
    }

    // before로 받은 배치는 서버가 체크포인트를 모르므로, 경계에 걸친 메시지를 직접 걸러낸다
    private static List<Map<String, Object>> filterAfterCheckpoint(List<Map<String, Object>> page, Instant checkpoint) {
        if (checkpoint == null) {
            return page;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> message : page) {
            Instant timestamp = parseMessageTimestamp(message);
            if (timestamp != null && timestamp.isAfter(checkpoint)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private static Instant parseMessageTimestamp(Map<String, Object> message) {
        Object timestamp = message.get("timestamp");
        if (!(timestamp instanceof String text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    // 시스템 메시지(type 0·19 외)·봇/웹훅 메시지 제외
    private static List<Map<String, Object>> filterNoise(List<Map<String, Object>> messages) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if (!isNoise(message)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private static boolean isNoise(Map<String, Object> message) {
        Object type = message.get("type");
        if (!(type instanceof Number number) || !ALLOWED_MESSAGE_TYPES.contains(number.intValue())) {
            return true;
        }
        Map<String, Object> author = (Map<String, Object>) message.get("author");
        return author != null && Boolean.TRUE.equals(author.get("bot"));
    }

    // Instant → snowflake: (epochMilli - Discord epoch) << 22. 하위 22비트는 0이라, 같은 밀리초에
    // 생성된 실제 id보다 작거나 같아 "그 시각 이후 전부"를 놓치지 않는 경계값이 된다.
    static String instantToSnowflake(Instant instant) {
        return Long.toString((instant.toEpochMilli() - DISCORD_EPOCH_MS) << 22);
    }

    static Instant snowflakeToInstant(String snowflake) {
        long value = Long.parseLong(snowflake);
        return Instant.ofEpochMilli((value >> 22) + DISCORD_EPOCH_MS);
    }

    private <T> T executeWithRateLimitRetry(Supplier<T> request) {
        int attempts = 0;
        while (true) {
            try {
                T result = request.get();
                rateLimiter.afterRequest();
                return result;
            } catch (WebClientResponseException.TooManyRequests exception) {
                attempts++;
                if (attempts > MAX_RETRY_ON_RATE_LIMIT) {
                    throw exception;
                }
                double retryAfterSeconds = parseRetryAfter(exception);
                log.warn("Discord rate limit(429) — {}초 대기 후 재시도 ({}/{})",
                        retryAfterSeconds, attempts, MAX_RETRY_ON_RATE_LIMIT);
                rateLimiter.awaitRetry(retryAfterSeconds);
            }
        }
    }

    private static double parseRetryAfter(WebClientResponseException.TooManyRequests exception) {
        try {
            Map<String, Object> body = exception.getResponseBodyAs(new ParameterizedTypeReference<>() {});
            Object retryAfter = body == null ? null : body.get("retry_after");
            if (retryAfter instanceof Number number) {
                return number.doubleValue();
            }
        } catch (RuntimeException ignored) {
            // 응답 본문 파싱 실패 시 기본값으로 폴백
        }
        return 1.0;
    }
}
