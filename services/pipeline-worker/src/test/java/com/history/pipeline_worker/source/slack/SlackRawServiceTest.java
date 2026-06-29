package com.history.pipeline_worker.source.slack;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
                new SlackRateLimiter(0, 0, 0),
                Duration.ofMinutes(5)
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
    @DisplayName("TTL 내 재호출은 users.list를 재조회하지 않고 캐시를 재사용한다")
    void cachesUserMapWithinTtl() {
        AtomicInteger usersListCalls = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if ("/users.list".equals(request.url().getPath())) {
                        usersListCalls.incrementAndGet();
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0), Duration.ofMinutes(5));
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());

        service.prepareFetchContext(request, null);
        service.prepareFetchContext(request, null);

        assertThat(usersListCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL=0이면 캐시가 비활성화되어 매번 users.list를 재조회한다")
    void refetchesUserMapWhenTtlZero() {
        AtomicInteger usersListCalls = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if ("/users.list".equals(request.url().getPath())) {
                        usersListCalls.incrementAndGet();
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });
        SlackRawService service = new SlackRawService(
                webClientBuilder, "https://slack.example", new SlackRateLimiter(0, 0, 0), Duration.ZERO);
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());

        service.prepareFetchContext(request, null);
        service.prepareFetchContext(request, null);

        assertThat(usersListCalls.get()).isEqualTo(2);
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
                      "channels": [
                        {"id": "C1", "name": "general"}
                      ],
                      "response_metadata": {"next_cursor": ""}
                    }
                    """;
            case "/conversations.history" -> """
                    {
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
