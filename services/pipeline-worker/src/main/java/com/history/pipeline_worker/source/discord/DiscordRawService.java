package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
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

    public record DiscordMessagePage(List<Map<String, Object>> messages, String nextCursor) {}

    /**
     * 채널 메시지를 <b>한 페이지</b> 받는다. {@code cursor}가 {@code null}이면 체크포인트에서 시작하고,
     * 이후에는 직전 페이지의 {@code nextCursor}를 그대로 넘긴다. {@code nextCursor}가 {@code null}이면
     * 그 채널은 끝이다.
     * <p>
     * {@code after}는 커서 바로 다음부터 앞으로 전진하며 채운다 — 백로그가 페이지 크기(100)를 넘으면
     * <b>가장 오래된 쪽 100개</b>가 먼저 오고, 최신→과거 내림차순은 배치 <b>안쪽</b> 정렬일 뿐이다
     * (실측 확정 — docs/discord-integration.md 「확인 완료」 4). 따라서 가득 찬 페이지를 받으면 그 배치의
     * 가장 큰 id가 다음 커서가 된다. 서버가 커서 이후만 걸러 주므로 클라이언트 경계 필터링은 필요 없다.
     * <p>
     * 채널 전체를 모아 반환하지 않는 이유는 Slack과 같다 — 발행 배치와 메모리 점유가 채널 크기에
     * 비례하면 큰 채널에서 confirm 타임아웃으로 영구 실패한다. 호출부가 페이지마다 발행한다.
     */
    public DiscordMessagePage fetchMessagePage(DiscordFetchContext context, Map<String, Object> channel, String cursor) {
        String channelId = (String) channel.get("id");
        String after = cursor == null ? afterCursor(context.lastScannedAt()) : cursor;

        List<Map<String, Object>> page = requestMessages(context.auth(), channelId, after);

        String nextCursor = null;
        if (page.size() >= PAGE_SIZE) {
            // 다음 커서는 노이즈 필터 이전 원본에서 뽑는다 — 100건이 전부 봇/시스템 메시지인 페이지에서
            // 필터 결과로 커서를 정하면 전진하지 못해 같은 페이지를 무한히 다시 받는다.
            nextCursor = maxMessageId(page);
            if (!advances(nextCursor, after)) {
                // after가 커서 자신을 제외하므로 여기 도달하면 안 된다. 다만 이 코드는 이미 한 번 Discord의
                // 커서 의미를 잘못 읽은 적이 있어(「확인 완료」 4) 방어한다. 조용히 그 채널만 끊으면
                // 다른 채널이 공용 커서를 전진시켜 남은 구간이 영구 누락된다 — 그게 이번에 고친 버그다.
                // 그래서 예외로 올려 checkpoint 전체를 멈춘다(계약: 전진하지 않아야 다음 수집에서 재시도).
                throw new IllegalStateException(
                        "Discord 커서가 전진하지 않는다 — channelId=" + channelId
                                + ", after=" + after + ", maxId=" + nextCursor);
            }
        }
        return new DiscordMessagePage(filterNoise(page), nextCursor);
    }

    private List<Map<String, Object>> requestMessages(String auth, String channelId, String afterCursor) {
        List<Map<String, Object>> page = executeWithRateLimitRetry(() -> webClient.get()
                .uri("/channels/{channelId}/messages?limit=" + PAGE_SIZE + "&after={afterCursor}",
                        channelId, afterCursor)
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

    // 다음 커서는 배치의 최대 id다. 응답 정렬(최신→과거)에 기대 첫 원소를 집지 않는다 — 정렬을
    // 선택 구간으로 오해한 것이 애초 페이지네이션 버그의 원인이었다. max는 순서와 무관하고 비용도 없다.
    private static String maxMessageId(List<Map<String, Object>> page) {
        String max = null;
        for (Map<String, Object> message : page) {
            if (message.get("id") instanceof String id && advances(id, max)) {
                max = id;
            }
        }
        return max;
    }

    // candidate가 current보다 뒤인가. current가 null이면 첫 후보라 참. snowflake로 못 읽으면 거짓 —
    // 판단이 안 서면 전진하지 않는 쪽이 안전하다(무한 루프 대신 멈춘다).
    private static boolean advances(String candidate, String current) {
        if (candidate == null) {
            return false;
        }
        try {
            long value = Long.parseLong(candidate);
            return current == null || value > Long.parseLong(current);
        } catch (NumberFormatException exception) {
            return false;
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
