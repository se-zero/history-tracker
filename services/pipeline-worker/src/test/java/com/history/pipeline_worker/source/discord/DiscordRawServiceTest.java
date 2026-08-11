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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordRawServiceTest {

    private static final String GUILD_ID = "G1";

    @Test
    @DisplayName("Instant → snowflake — 하위 22비트가 0이라 같은 밀리초의 실제 id를 놓치지 않는 경계값이 된다")
    void instantToSnowflake_zeroesSequenceBitsSoBoundaryStaysInclusive() {
        // 2026-08-08T05:25:44.423Z → (1786166744423 - 1420070400000) << 22
        String snowflake = DiscordRawService.instantToSnowflake(Instant.parse("2026-08-08T05:25:44.423Z"));

        assertThat(snowflake).isEqualTo("1535519361798766592");
        assertThat(Long.parseLong(snowflake) & ((1L << 22) - 1)).isZero();
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

        List<Map<String, Object>> channels = service.fetchChannels(context(null));

        assertThat(channels).hasSize(3);
        assertThat(channels).extracting(c -> c.get("id")).containsExactly("C1", "C2", "T1");
        assertThat(channels.get(0)).containsEntry("isThread", false);
        assertThat(channels.get(2)).containsEntry("isThread", true);
    }

    @Test
    @DisplayName("페이지가 가득 차지 않으면 nextCursor가 null이다 — 그 채널은 끝")
    void fetchMessagePage_partialPage_hasNoNextCursor() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            // checkpoint가 없으면(초기 수집) snowflake "0"부터 — 전체 히스토리 대상
            assertThat(request.url().getQuery()).contains("after=0");
            return Mono.just(jsonArrayResponse(buildMessages(Instant.parse("2026-08-08T00:00:00Z"), 3, 1)));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(null), channel(), null);

        assertThat(page.messages()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("가득 찬 페이지(100)면 nextCursor가 그 배치의 최대 id다 — before로 뒤집지 않는다")
    void fetchMessagePage_fullPage_nextCursorIsMaxId() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        Instant checkpoint = now.minusSeconds(3600L * 200);
        List<Map<String, Object>> full = buildMessages(now, 100, 1);

        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            String query = request.url().getQuery();
            assertThat(query).doesNotContain("before=");
            // checkpoint는 snowflake로 변환돼 서버사이드 증분 필터가 된다
            assertThat(query).contains("after=" + DiscordRawService.instantToSnowflake(checkpoint));
            return Mono.just(jsonArrayResponse(full));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(checkpoint), channel(), null);

        assertThat(page.messages()).hasSize(100);
        assertThat(page.nextCursor()).isEqualTo(maxId(full));
    }

    @Test
    @DisplayName("커서를 넘겨받으면 그것을 after로 쓴다 — checkpoint는 첫 페이지에만 쓰인다")
    void fetchMessagePage_withCursor_usesCursorAsAfterInsteadOfCheckpoint() {
        Instant checkpoint = Instant.parse("2026-08-01T00:00:00Z");
        String cursor = "1535519361798766592";

        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            String query = request.url().getQuery();
            assertThat(query).contains("after=" + cursor);
            assertThat(query).doesNotContain("after=" + DiscordRawService.instantToSnowflake(checkpoint));
            return Mono.just(jsonArrayResponse(List.of()));
        });
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(checkpoint), channel(), cursor);

        assertThat(page.messages()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("nextCursor는 배열 위치가 아니라 최대 id로 정한다 — 응답 정렬이 뒤집혀도 뒤로 가지 않는다")
    void fetchMessagePage_nextCursorUsesMaxId_notArrayPosition() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        // 과거→최신 오름차순으로 뒤집어 준다. 첫 원소를 커서로 집으면 가장 오래된 id라 무한 반복된다.
        List<Map<String, Object>> ascending = new ArrayList<>(buildMessages(now, 100, 1));
        Collections.reverse(ascending);

        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonArrayResponse(ascending)));
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(null), channel(), null);

        assertThat(page.nextCursor()).isEqualTo(maxId(ascending));
    }

    @Test
    @DisplayName("가득 찬 페이지가 전부 노이즈여도 nextCursor는 전진한다 — 필터 이전 원본으로 정하기 때문")
    void fetchMessagePage_fullPageOfNoise_stillAdvancesCursor() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        List<Map<String, Object>> allBots = buildBotMessages(now, 100);

        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonArrayResponse(allBots)));
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(null), channel(), null);

        assertThat(page.messages()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo(maxId(allBots));
    }

    @Test
    @DisplayName("가득 찬 페이지인데 커서가 전진하지 않으면 예외 — 무한 루프도, 조용한 누락도 만들지 않는다")
    void fetchMessagePage_cursorWouldNotAdvance_throwsInsteadOfLooping() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        // 응답의 모든 id가 요청 커서보다 작다 = after 계약이 깨진 상황. 그대로 두면 같은 페이지를 반복한다.
        List<Map<String, Object>> full = buildMessages(now, 100, 1);
        String cursorAheadOfEverything = DiscordRawService.instantToSnowflake(now.plusSeconds(3600));

        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonArrayResponse(full)));
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        assertThatThrownBy(() -> service.fetchMessagePage(context(null), channel(), cursorAheadOfEverything))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("커서가 전진하지 않는다");
    }

    @Test
    @DisplayName("id를 snowflake로 못 읽는 가득 찬 페이지도 조용히 끊지 않고 예외로 올린다")
    void fetchMessagePage_unparseableIds_throwsInsteadOfSilentlyTruncating() {
        List<Map<String, Object>> full = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            full.add(rawMessage("NOT_A_SNOWFLAKE_" + i, 0, false));
        }

        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonArrayResponse(full)));
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        assertThatThrownBy(() -> service.fetchMessagePage(context(null), channel(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("시스템 메시지(type 0·19 외)와 봇 메시지는 제외한다")
    void fetchMessagePage_filtersSystemAndBotMessages() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(rawMessage("M1", 0, false));   // 일반 — 포함
        raw.add(rawMessage("M2", 19, false));  // 답글 — 포함
        raw.add(rawMessage("M3", 7, false));   // 시스템(멤버 가입) — 제외
        raw.add(rawMessage("M4", 0, true));    // 봇 메시지 — 제외

        WebClient.Builder builder = WebClient.builder().exchangeFunction(
                request -> Mono.just(jsonArrayResponse(raw)));
        DiscordRawService service = new DiscordRawService(builder, "https://discord.example", rateLimiter());

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(null), channel(), null);

        assertThat(page.messages()).extracting(m -> m.get("id")).containsExactly("M1", "M2");
    }

    @Test
    @DisplayName("429 응답이면 retry_after만큼 대기 후 재시도해 결국 성공한다")
    void fetchMessagePage_rateLimited_retriesAndSucceeds() {
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

        DiscordRawService.DiscordMessagePage page = service.fetchMessagePage(context(null), channel(), null);

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(page.messages()).hasSize(1);
    }

    private DiscordRateLimiter rateLimiter() {
        return new DiscordRateLimiter(0);
    }

    private DiscordRawService.DiscordFetchContext context(Instant lastScannedAt) {
        return new DiscordRawService.DiscordFetchContext("Bot token", GUILD_ID, lastScannedAt);
    }

    private Map<String, Object> channel() {
        return Map.of("id", "C1", "name", "일반", "isThread", false);
    }

    private String maxId(List<Map<String, Object>> page) {
        return page.stream()
                .map(m -> (String) m.get("id"))
                .max(Comparator.comparingLong(Long::parseLong))
                .orElseThrow();
    }

    private List<Map<String, Object>> buildMessages(Instant startInclusive, int count, int hourStep) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant timestamp = startInclusive.minusSeconds(3600L * hourStep * i);
            messages.add(rawMessageAt(DiscordRawService.instantToSnowflake(timestamp), timestamp, false));
        }
        return messages;
    }

    private List<Map<String, Object>> buildBotMessages(Instant startInclusive, int count) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant timestamp = startInclusive.minusSeconds(3600L * i);
            messages.add(rawMessageAt(DiscordRawService.instantToSnowflake(timestamp), timestamp, true));
        }
        return messages;
    }

    private Map<String, Object> rawMessageAt(String id, Instant timestamp, boolean bot) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("type", 0);
        message.put("timestamp", timestamp.toString());
        message.put("content", "text");
        message.put("author", Map.of("id", "U1", "username", "alice", "bot", bot));
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
