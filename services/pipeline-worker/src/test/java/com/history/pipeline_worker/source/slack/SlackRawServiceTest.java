package com.history.pipeline_worker.source.slack;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SlackRawServiceTest {

    @Test
    @DisplayName("sample은 첫 채널 첫 페이지에서 checkpoint 이전 root thread라도 최신 reply가 있으면 replies를 수집")
    @SuppressWarnings("unchecked")
    void fetchSample_collectsNewRepliesFromOldThread() {
        AtomicReference<String> repliesQuery = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if ("/conversations.replies".equals(path)) {
                        repliesQuery.set(request.url().getQuery());
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });

        SlackRawService service = new SlackRawService(
                webClientBuilder,
                "https://slack.example",
                new SlackRateLimiter(0, 0, 0)
        );

        Map<String, Object> raw = service.fetchSample(new RawFetchRequest("Bearer token", null, Map.of()));

        List<Map<String, Object>> channels = (List<Map<String, Object>>) raw.get("channels");
        Map<String, Object> channel = channels.get(0);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) channel.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("ts", "1699999900.000000");

        List<Map<String, Object>> threads = (List<Map<String, Object>>) channel.get("threads");
        assertThat(threads).hasSize(1);
        List<Map<String, Object>> replies = (List<Map<String, Object>>) threads.get(0).get("replies");
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0)).containsEntry("ts", "1700000100.000000")
                .containsEntry("userName", "Alice");
        assertThat(repliesQuery.get()).doesNotContain("oldest=");
    }

    @Test
    @DisplayName("lastScannedAt이 있으면 history 쿼리에 (lastScannedAt - 14일)의 oldest를 붙인다")
    void fetchHistoryPage_withLastScannedAt_addsOldestMinusLookbackToHistoryUri() {
        AtomicReference<String> historyQuery = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    historyQuery.set(request.url().getQuery());
                    return Mono.just(jsonResponse("""
                            {
                              "ok": true,
                              "messages": [
                                {"user": "U1", "text": "hello", "ts": "1699999900.000000"}
                              ],
                              "response_metadata": {"next_cursor": ""}
                            }
                            """));
                });
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0));
        SlackRawService.SlackFetchContext context =
                new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());
        Instant lastScannedAt = Instant.ofEpochSecond(1700000000);

        service.fetchHistoryPage(context, channel(), null, lastScannedAt);

        // 14일 = 1,209,600초 → 1700000000 - 1209600 = 1698790400
        assertThat(historyQuery.get()).contains("oldest=1698790400.000000");
    }

    @Test
    @DisplayName("lastScannedAt이 없으면 history 쿼리에 oldest를 붙이지 않는다")
    void fetchHistoryPage_withoutLastScannedAt_omitsOldest() {
        AtomicReference<String> historyQuery = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    historyQuery.set(request.url().getQuery());
                    return Mono.just(jsonResponse("""
                            {
                              "ok": true,
                              "messages": [
                                {"user": "U1", "text": "hello", "ts": "1699999900.000000"}
                              ],
                              "response_metadata": {"next_cursor": ""}
                            }
                            """));
                });
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0));
        SlackRawService.SlackFetchContext context =
                new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());

        service.fetchHistoryPage(context, channel(), null, null);

        assertThat(historyQuery.get()).doesNotContain("oldest=");
    }

    @Test
    @DisplayName("replies의 oldest는 lookback을 빼지 않은 lastScannedAt 그대로 전달한다")
    void fetchHistoryPage_passesLastScannedAtToRepliesOldest() {
        AtomicReference<String> repliesQuery = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if ("/conversations.replies".equals(path)) {
                        repliesQuery.set(request.url().getQuery());
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0));
        SlackRawService.SlackFetchContext context =
                new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());
        // 루트 메시지(ts=1699999900)보다 과거라 checkpoint 이전이 아니므로 replies를 가져온다
        Instant lastScannedAt = Instant.ofEpochSecond(1699990000);

        service.fetchHistoryPage(context, channel(), null, lastScannedAt);

        assertThat(repliesQuery.get()).contains("oldest=1699990000.000000");
    }

    @Test
    @DisplayName("prepareFetchContext를 두 번 호출하면 users.list를 매번 재조회한다 — 실행 간 캐시가 없다")
    void prepareFetchContext_calledTwice_refetchesUserListEachTime() {
        AtomicInteger usersListCalls = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if ("/users.list".equals(request.url().getPath())) {
                        usersListCalls.incrementAndGet();
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0));
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());

        service.prepareFetchContext(request);
        service.prepareFetchContext(request);

        assertThat(usersListCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("history 응답이 ok:false면 Slack error를 메시지에 담은 IllegalStateException을 던진다")
    void fetchHistoryPage_okFalse_throwsIllegalStateWithSlackError() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse("""
                        {"ok": false, "error": "missing_scope"}
                        """)));
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0));
        SlackRawService.SlackFetchContext context =
                new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());

        assertThatThrownBy(() -> service.fetchHistoryPage(context, channel(), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing_scope");
    }

    @Test
    @DisplayName("users.list 응답이 ok:false면 prepareFetchContext가 IllegalStateException을 던진다")
    void prepareFetchContext_usersListOkFalse_throwsIllegalState() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse("""
                        {"ok": false, "error": "invalid_auth"}
                        """)));
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0));
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());

        assertThatThrownBy(() -> service.prepareFetchContext(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid_auth");
    }

    @Test
    @DisplayName("history가 429면 Retry-After 헤더만큼 대기 후 재시도해 결국 성공한다")
    void fetchHistoryPage_rateLimited_retriesAfterRetryAfterAndSucceeds() {
        RateLimitedFixture fixture = historyRateLimitedOnceThenSucceeds();

        SlackRawService.SlackHistoryPage page =
                fixture.service().fetchHistoryPage(fixture.context(), channel(), null, null);

        assertThat(fixture.historyCalls().get()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) page.channelData().get("messages");
        assertThat(messages).hasSize(1);
        verify(fixture.rateLimiter()).awaitRetry(7L);
        // 승격된 pacing이 성공 후 고정 대기(afterXxx)까지 실제로 전달되는지 — 이 연결선이 빠져도 다른 테스트는 통과한다
        verify(fixture.rateLimiter()).afterConversationsHistory(7000L);
    }

    @Test
    @DisplayName("429 재시도 시 Retry-After 헤더 값(초→ms)으로 해당 endpoint의 pacing을 승격한다")
    void fetchHistoryPage_rateLimited_escalatesPacingFromHeader() {
        RateLimitedFixture fixture = historyRateLimitedOnceThenSucceeds();

        fixture.service().fetchHistoryPage(fixture.context(), channel(), null, null);

        assertThat(fixture.context().pacing().minDelayMs(SlackPacing.Endpoint.HISTORY)).isEqualTo(7000L);
    }

    @Test
    @DisplayName("429가 계속되면 최대 재시도(3회) 후 원 예외를 전파한다")
    void fetchHistoryPage_rateLimitPersists_throwsAfterMaxRetries() {
        AtomicInteger historyCalls = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    historyCalls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                            .header("Retry-After", "7")
                            .build());
                });
        SlackRateLimiter rateLimiter = mock(SlackRateLimiter.class);
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", rateLimiter);
        SlackRawService.SlackFetchContext context =
                new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());

        assertThatThrownBy(() -> service.fetchHistoryPage(context, channel(), null, null))
                .isInstanceOf(WebClientResponseException.TooManyRequests.class);

        assertThat(historyCalls.get()).isEqualTo(4);
        verify(rateLimiter, times(3)).awaitRetry(7L);
    }

    @Test
    @DisplayName("Retry-After 헤더가 정수 초 문자열이면 그대로 파싱한다")
    void parseRetryAfterSeconds_parsesIntegerSeconds() {
        assertThat(SlackRawService.parseRetryAfterSeconds("30")).isEqualTo(30L);
    }

    @Test
    @DisplayName("Retry-After 헤더가 없거나 형식이 잘못되면 60초로 폴백한다")
    void parseRetryAfterSeconds_missingOrMalformedHeader_fallsBackTo60() {
        assertThat(SlackRawService.parseRetryAfterSeconds(null)).isEqualTo(60L);
        assertThat(SlackRawService.parseRetryAfterSeconds("abc")).isEqualTo(60L);
        assertThat(SlackRawService.parseRetryAfterSeconds("-5")).isEqualTo(60L);
    }

    // history 1회차만 429(Retry-After: 7)이고 2회차부터 정상 응답하는 공통 시나리오
    private RateLimitedFixture historyRateLimitedOnceThenSucceeds() {
        AtomicInteger historyCalls = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if (historyCalls.incrementAndGet() == 1) {
                        return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                                .header("Retry-After", "7")
                                .build());
                    }
                    return Mono.just(jsonResponse("""
                            {
                              "ok": true,
                              "messages": [
                                {
                                  "user": "U1",
                                  "text": "hello",
                                  "ts": "1699999900.000000"
                                }
                              ],
                              "response_metadata": {"next_cursor": ""}
                            }
                            """));
                });
        SlackRateLimiter rateLimiter = mock(SlackRateLimiter.class);
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", rateLimiter);
        SlackRawService.SlackFetchContext context =
                new SlackRawService.SlackFetchContext("Bearer token", Map.of(), new SlackPacing());
        return new RateLimitedFixture(service, context, historyCalls, rateLimiter);
    }

    private record RateLimitedFixture(SlackRawService service, SlackRawService.SlackFetchContext context,
                                       AtomicInteger historyCalls, SlackRateLimiter rateLimiter) {}

    private Map<String, Object> channel() {
        return Map.of("id", "C1", "name", "general");
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build();
    }

    private String responseFor(ClientRequest request) {
        return switch (request.url().getPath()) {
            case "/users.list" -> """
                    {
                      "ok": true,
                      "members": [
                        {
                          "id": "U1",
                          "name": "alice",
                          "profile": {
                            "display_name": "Alice",
                            "email": "alice@example.com"
                          }
                        }
                      ],
                      "response_metadata": {"next_cursor": ""}
                    }
                    """;
            case "/conversations.list" -> """
                    {
                      "ok": true,
                      "channels": [
                        {"id": "C1", "name": "general"}
                      ],
                      "response_metadata": {"next_cursor": ""}
                    }
                    """;
            case "/conversations.history" -> """
                    {
                      "ok": true,
                      "messages": [
                        {
                          "user": "U1",
                          "text": "old root",
                          "ts": "1699999900.000000",
                          "reply_count": 1,
                          "latest_reply": "1700000100.000000"
                        }
                      ],
                      "response_metadata": {"next_cursor": ""}
                    }
                    """;
            case "/conversations.replies" -> """
                    {
                      "ok": true,
                      "messages": [
                        {
                          "user": "U1",
                          "text": "old root",
                          "ts": "1699999900.000000"
                        },
                        {
                          "user": "U1",
                          "text": "new reply",
                          "ts": "1700000100.000000"
                        }
                      ],
                      "response_metadata": {"next_cursor": ""}
                    }
                    """;
            default -> throw new IllegalArgumentException("Unexpected Slack API path: " + request.url().getPath());
        };
    }
}
