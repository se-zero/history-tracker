package com.history.pipeline_worker.source.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordRawServiceTest {

    private static final String GUILD_ID = "G1";

    @Test
    @DisplayName("snowflake ↔ Instant 왕복 변환 — 밀리초 정밀도까지 보존된다")
    void snowflakeConversion_roundTripsAtMillisecondPrecision() {
        Instant instant = Instant.parse("2026-08-08T05:25:44.423Z");

        String snowflake = DiscordRawService.instantToSnowflake(instant);
        Instant restored = DiscordRawService.snowflakeToInstant(snowflake);

        assertThat(restored).isEqualTo(instant);
    }

    @Test
    @DisplayName("길드 채널 중 텍스트(type 0·5)만 포함하고, 활성 스레드도 합쳐 반환한다")
    void fetchChannels_mergesTextChannelsAndActiveThreads() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            String path = request.url().getPath();
            if (path.equals("/guilds/" + GUILD_ID + "/channels")) {
                return Mono.just(jsonResponse("""
                        [
                          {"id": "C1", "name": "일반", "type": 0},
                          {"id": "C2", "name": "공지", "type": 5},
                          {"id": "C3", "name": "음성채널", "type": 2},
                          {"id": "C4", "name": "카테고리", "type": 4}
                        ]
                        """));
            }
            if (path.equals("/guilds/" + GUILD_ID + "/threads/active")) {
                return Mono.just(jsonResponse("""
                        {"threads": [{"id": "T1", "name": "스레드: 기획"}]}
                        """));
            }
            throw new IllegalArgumentException("Unexpected path: " + path);
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> channels = service.fetchChannels(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, null));

        assertThat(channels).hasSize(3);
        assertThat(channels).extracting(c -> c.get("id")).containsExactly("C1", "C2", "T1");
        assertThat(channels.get(0)).containsEntry("isThread", false);
        assertThat(channels.get(2)).containsEntry("isThread", true);
    }

    @Test
    @DisplayName("페이지가 가득 차지 않으면(< 100) 한 번만 호출하고 끝낸다")
    void fetchChannelMessages_partialPage_singleCall() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            assertThat(request.url().getQuery()).contains("after=");
            return Mono.just(jsonArrayResponse(buildMessages(Instant.parse("2026-08-08T00:00:00Z"), 3, 1)));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> messages = service.fetchChannelMessages(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, null),
                Map.of("id", "C1", "name", "일반", "isThread", false));

        assertThat(messages).hasSize(3);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("가득 찬 페이지(100) 이후 아직 checkpoint에 못 닿았으면 before로 이어서 받는다")
    void fetchChannelMessages_fullPage_continuesWithBefore() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        // page1: now ~ now-99h (100건, 전부 checkpoint 이후) / page2: now-100h ~ now-149h (50건, 전부 checkpoint 이후)
        Instant checkpoint = now.minusSeconds(3600L * 200);
        List<Map<String, Object>> page1 = buildMessages(now, 100, 1);
        List<Map<String, Object>> page2 = buildMessages(now.minusSeconds(3600L * 100), 50, 1);

        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            int call = callCount.incrementAndGet();
            String query = request.url().getQuery();
            if (call == 1) {
                assertThat(query).contains("after=");
                return Mono.just(jsonArrayResponse(page1));
            }
            assertThat(query).contains("before=");
            return Mono.just(jsonArrayResponse(page2));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> messages = service.fetchChannelMessages(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, checkpoint),
                Map.of("id", "C1", "name", "일반", "isThread", false));

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(messages).hasSize(150);
    }

    @Test
    @DisplayName("before로 받은 배치 중 checkpoint 이하 메시지는 걸러낸다")
    void fetchChannelMessages_beforeBatch_filtersMessagesAtOrBeforeCheckpoint() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        // page1: now ~ now-99h (100건) / page2: now-100h ~ now-149h (50건, 시간당 1개)
        // checkpoint=now-120h → page2 중 now-100h~now-119h(20건)만 checkpoint 이후로 살아남는다
        Instant checkpoint = now.minusSeconds(3600L * 120);
        List<Map<String, Object>> page1 = buildMessages(now, 100, 1);
        List<Map<String, Object>> page2 = buildMessages(now.minusSeconds(3600L * 100), 50, 1);

        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            int call = callCount.incrementAndGet();
            return Mono.just(jsonArrayResponse(call == 1 ? page1 : page2));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> messages = service.fetchChannelMessages(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, checkpoint),
                Map.of("id", "C1", "name", "일반", "isThread", false));

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(messages).hasSize(120);
    }

    @Test
    @DisplayName("가득 찬 페이지의 가장 오래된 메시지가 이미 checkpoint 이하면 더 호출하지 않는다")
    void fetchChannelMessages_oldestAlreadyAtCheckpoint_stopsWithoutSecondCall() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        // page1: now ~ now-99h (100건). checkpoint을 그 범위 안(now-50h)에 두면 조기 종료해야 한다.
        Instant checkpoint = now.minusSeconds(3600L * 50);
        List<Map<String, Object>> page1 = buildMessages(now, 100, 1);

        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            return Mono.just(jsonArrayResponse(page1));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> messages = service.fetchChannelMessages(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, checkpoint),
                Map.of("id", "C1", "name", "일반", "isThread", false));

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(messages).hasSize(100);
    }

    @Test
    @DisplayName("시스템 메시지(type 0·19 외)와 봇 메시지는 제외한다")
    void fetchChannelMessages_filtersSystemAndBotMessages() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(rawMessage("M1", 0, false));   // 일반 — 포함
        raw.add(rawMessage("M2", 19, false));  // 답글 — 포함
        raw.add(rawMessage("M3", 7, false));   // 시스템(멤버 가입) — 제외
        raw.add(rawMessage("M4", 0, true));    // 봇 메시지 — 제외

        WebClient.Builder builder = WebClient.builder().exchangeFunction(
                request -> Mono.just(jsonArrayResponse(raw)));
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> messages = service.fetchChannelMessages(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, null),
                Map.of("id", "C1", "name", "일반", "isThread", false));

        assertThat(messages).extracting(m -> m.get("id")).containsExactly("M1", "M2");
    }

    @Test
    @DisplayName("429 응답이면 retry_after만큼 대기 후 재시도해 결국 성공한다")
    void fetchChannelMessages_rateLimited_retriesAndSucceeds() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            if (callCount.incrementAndGet() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {"message": "You are being rate limited.", "retry_after": 0.01, "global": false}
                                """)
                        .build());
            }
            return Mono.just(jsonArrayResponse(buildMessages(Instant.parse("2026-08-08T00:00:00Z"), 1, 1)));
        });
        // default-delay만 0으로 둬도 재시도 대기(awaitRetry)는 응답의 retry_after(0.01초)를 그대로 쓴다
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        List<Map<String, Object>> messages = service.fetchChannelMessages(
                new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, null),
                Map.of("id", "C1", "name", "일반", "isThread", false));

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(messages).hasSize(1);
    }

    private DiscordRateLimiter rateLimiter() {
        return new DiscordRateLimiter(0);
    }

    private List<Map<String, Object>> buildMessages(Instant startInclusive, int count, int hourStep) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant timestamp = startInclusive.minusSeconds(3600L * hourStep * i);
            messages.add(rawMessageAt(String.valueOf(DiscordRawService.instantToSnowflake(timestamp)), timestamp));
        }
        return messages;
    }

    private Map<String, Object> rawMessageAt(String id, Instant timestamp) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("type", 0);
        message.put("timestamp", timestamp.toString());
        message.put("content", "text");
        message.put("author", Map.of("id", "U1", "username", "alice", "bot", false));
        return message;
    }

    private Map<String, Object> rawMessage(String id, int type, boolean bot) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("type", type);
        message.put("timestamp", "2026-08-08T00:00:00Z");
        message.put("content", "text");
        message.put("author", Map.of("id", "U1", "username", "alice", "bot", bot));
        return message;
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build();
    }

    private ClientResponse jsonArrayResponse(List<Map<String, Object>> messages) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) json.append(",");
            Map<String, Object> m = messages.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> author = (Map<String, Object>) m.get("author");
            json.append("""
                    {"id": "%s", "type": %s, "timestamp": "%s", "content": "%s",
                     "author": {"id": "%s", "username": "%s", "bot": %s}}
                    """.formatted(
                    m.get("id"), m.get("type"), m.get("timestamp"), m.get("content"),
                    author.get("id"), author.get("username"), author.get("bot")));
        }
        json.append("]");
        return jsonResponse(json.toString());
    }
}
