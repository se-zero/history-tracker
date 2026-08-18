package com.history.pipeline_worker.source.notion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GoogleChatRawServiceTest 컨벤션(WebClient.Builder.exchangeFunction 스텁)을 미러한다. NotionRawService는
// 필드 하나짜리 WebClient를 생성자에서 굳히므로(GoogleChat·Discord와 같은 모양), context가 아니라
// 서비스 생성 시점에 builder를 주입한다.
class NotionRawServiceTest {

    private static final String BASE_URL = "https://api.notion.test/v1";
    private static final String VERSION = "2026-03-11";

    // ─── searchPages ────────────────────────────────────────────────────────

    @Test
    @DisplayName("검색 요청은 POST /search로 filter=page·sort=last_edited_time desc·page_size=100, "
            + "Bearer·Notion-Version 헤더를 담는다")
    void searchPages_firstRequest_bodyAndHeaders() {
        List<Map<String, Object>> capturedBodies = new java.util.ArrayList<>();
        List<String> capturedAuthHeaders = new java.util.ArrayList<>();
        List<String> capturedVersionHeaders = new java.util.ArrayList<>();
        List<String> capturedUris = new java.util.ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            capturedUris.add(request.url().toString());
            capturedAuthHeaders.add(request.headers().getFirst("Authorization"));
            capturedVersionHeaders.add(request.headers().getFirst("Notion-Version"));
            capturedBodies.add(captureRequestBody(request));
            return Mono.just(jsonResponse("{ \"results\": [], \"has_more\": false }"));
        });
        NotionRawService service = service(builder);

        service.searchPages(new NotionRawService.NotionFetchContext("Bearer notion-token", null), null);

        assertThat(capturedUris).containsExactly(BASE_URL + "/search");
        assertThat(capturedAuthHeaders).containsExactly("Bearer notion-token");
        assertThat(capturedVersionHeaders).containsExactly(VERSION);
        Map<String, Object> body = capturedBodies.get(0);
        assertThat(body).doesNotContainKey("start_cursor");
        assertThat(body.get("page_size")).isEqualTo(100);
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) body.get("filter");
        assertThat(filter).containsEntry("property", "object").containsEntry("value", "page");
        @SuppressWarnings("unchecked")
        Map<String, Object> sort = (Map<String, Object>) body.get("sort");
        assertThat(sort).containsEntry("timestamp", "last_edited_time").containsEntry("direction", "descending");
    }

    @Test
    void searchPages_withCursor_sendsStartCursorInBody() {
        List<Map<String, Object>> capturedBodies = new java.util.ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            capturedBodies.add(captureRequestBody(request));
            return Mono.just(jsonResponse("{ \"results\": [], \"has_more\": false }"));
        });
        NotionRawService service = service(builder);

        service.searchPages(new NotionRawService.NotionFetchContext("Bearer t", null), "cursor-xyz");

        assertThat(capturedBodies.get(0)).containsEntry("start_cursor", "cursor-xyz");
    }

    @Test
    @DisplayName("checkpoint보다 오래된(<=) 항목을 만나면 그 지점에서 끊고 next_cursor를 null로 강제한다 — 무한 재발행 방지")
    void searchPages_reachesCheckpoint_stopsAndNullsNextCursor() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                {
                  "results": [
                    {"object":"page","id":"newer","last_edited_time":"2026-08-10T00:00:00.000Z"},
                    {"object":"page","id":"boundary","last_edited_time":"2026-08-07T00:00:00.000Z"},
                    {"object":"page","id":"older","last_edited_time":"2026-08-05T00:00:00.000Z"}
                  ],
                  "next_cursor": "should-be-ignored",
                  "has_more": true
                }
                """)));
        NotionRawService service = service(builder);
        Instant checkpoint = Instant.parse("2026-08-07T00:00:00.000Z");

        NotionRawService.NotionSearchPageResult result = service.searchPages(
                new NotionRawService.NotionFetchContext("Bearer t", checkpoint), null);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).get("id")).isEqualTo("newer");
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void searchPages_noCheckpoint_keepsAllResultsRegardlessOfAge() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                { "results": [{"object":"page","id":"a","last_edited_time":"2020-01-01T00:00:00.000Z"}],
                  "has_more": false }
                """)));
        NotionRawService service = service(builder);

        NotionRawService.NotionSearchPageResult result = service.searchPages(
                new NotionRawService.NotionFetchContext("Bearer t", null), null);

        assertThat(result.pages()).hasSize(1);
    }

    @Test
    void searchPages_hasMoreFalse_nextCursorIsNull() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                { "results": [{"object":"page","id":"a","last_edited_time":"2026-01-01T00:00:00.000Z"}],
                  "next_cursor": "ignored", "has_more": false }
                """)));
        NotionRawService service = service(builder);

        NotionRawService.NotionSearchPageResult result = service.searchPages(
                new NotionRawService.NotionFetchContext("Bearer t", null), null);

        assertThat(result.nextCursor()).isNull();
    }

    // ─── fetchPageBody (블록 재귀) ──────────────────────────────────────────

    @Test
    @DisplayName("자식 블록을 재귀 조회해 줄바꿈으로 이어붙인다")
    void fetchPageBody_rendersAndJoinsChildBlocks() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                {
                  "results": [
                    {"id":"b1","type":"heading_1","has_children":false,
                     "heading_1":{"rich_text":[{"plain_text":"인증"}]}},
                    {"id":"b2","type":"paragraph","has_children":false,
                     "paragraph":{"rich_text":[{"plain_text":"본문"}]}}
                  ],
                  "has_more": false
                }
                """)));
        NotionRawService service = service(builder);

        String body = service.fetchPageBody(new NotionRawService.NotionFetchContext("Bearer t", null), "page-1");

        assertThat(body).isEqualTo("# 인증\n본문\n");
    }

    @Test
    @DisplayName("child_page는 has_children=true여도 재귀하지 않는다 — 하위 페이지 본문 중복·임베딩 비용 배가 방지")
    void fetchPageBody_doesNotRecurseIntoChildPage() {
        List<String> requestedPaths = new java.util.ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requestedPaths.add(request.url().getPath());
            return Mono.just(jsonResponse("""
                    {
                      "results": [
                        {"id":"child-page-1","type":"child_page","has_children":true,
                         "child_page":{"title":"하위 문서"}}
                      ],
                      "has_more": false
                    }
                    """));
        });
        NotionRawService service = service(builder);

        String body = service.fetchPageBody(new NotionRawService.NotionFetchContext("Bearer t", null), "page-1");

        assertThat(body).isEqualTo("하위 문서\n");
        // /blocks/page-1/children 한 번만 호출된다 — child_page의 내부(하위 페이지 본문)는 다시 조회하지 않는다.
        assertThat(requestedPaths).containsExactly("/v1/blocks/page-1/children");
    }

    @Test
    @DisplayName("재귀 깊이 상한(5단) 이후에는 자식을 더 조회하지 않는다")
    void fetchPageBody_stopsRecursionAtMaxDepth() {
        Map<String, String> nextBlockByParent = Map.of(
                "page-1", "L1", "L1", "L2", "L2", "L3", "L3", "L4", "L4", "L5", "L5", "L6");
        List<String> requestedParentIds = new java.util.ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            String path = request.url().getPath();
            String parentId = path.substring("/v1/blocks/".length(), path.length() - "/children".length());
            requestedParentIds.add(parentId);
            String childId = nextBlockByParent.get(parentId);
            String responseBody = childId == null
                    ? "{ \"results\": [], \"has_more\": false }"
                    : ("""
                            { "results": [
                                {"id":"%s","type":"paragraph","has_children":true,
                                 "paragraph":{"rich_text":[{"plain_text":"%s"}]}}
                              ], "has_more": false }
                            """).formatted(childId, childId);
            return Mono.just(jsonResponse(responseBody));
        });
        NotionRawService service = service(builder);

        String body = service.fetchPageBody(new NotionRawService.NotionFetchContext("Bearer t", null), "page-1");

        // depth 1~5 블록(L1~L5)까지만 렌더된다 — L5는 has_children=true지만 depth 6 조회가
        // 상한에 막혀 L6은 나타나지 않는다.
        assertThat(body).contains("L1").contains("L2").contains("L3").contains("L4").contains("L5");
        assertThat(body).doesNotContain("L6");
        // "L5"의 자식을 조회하는 "/blocks/L5/children" 호출 자체가 없어야 한다(깊이 상한이 호출 전에 막는다).
        assertThat(requestedParentIds).doesNotContain("L5");
    }

    @Test
    @DisplayName("블록 개수 상한(2000)을 넘는 항목은 잘라낸다")
    void fetchPageBody_truncatesAtMaxBlockCount() {
        StringBuilder resultsJson = new StringBuilder();
        for (int i = 0; i < 2001; i++) {
            if (i > 0) resultsJson.append(",");
            resultsJson.append("{\"id\":\"b").append(i).append("\",\"type\":\"paragraph\",\"has_children\":false,")
                    .append("\"paragraph\":{\"rich_text\":[{\"plain_text\":\"L").append(i).append("\"}]}}");
        }
        String responseBody = "{ \"results\": [" + resultsJson + "], \"has_more\": false }";
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse(responseBody)));
        NotionRawService service = service(builder);

        String body = service.fetchPageBody(new NotionRawService.NotionFetchContext("Bearer t", null), "page-1");

        long lineCount = body.lines().count();
        assertThat(lineCount).isEqualTo(2000);
        assertThat(body).contains("L1999").doesNotContain("L2000");
    }

    // ─── fetchAllUsers ──────────────────────────────────────────────────────

    @Test
    void fetchAllUsers_paginatesAndBuildsIdToUserMap() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            String responseBody = callCount.getAndIncrement() == 0
                    ? """
                      { "results": [{"object":"user","id":"u1","type":"person","name":"Alice",
                                     "person":{"email":"alice@example.com"}}],
                        "next_cursor": "cursor-2", "has_more": true }
                      """
                    : """
                      { "results": [{"object":"user","id":"u2","type":"bot","name":"Bot"}], "has_more": false }
                      """;
            return Mono.just(jsonResponse(responseBody));
        });
        NotionRawService service = service(builder);

        Map<String, NotionRawService.NotionUser> users = service.fetchAllUsers("Bearer t");

        assertThat(users).hasSize(2);
        assertThat(users.get("u1")).isEqualTo(new NotionRawService.NotionUser("Alice", "alice@example.com", false));
        assertThat(users.get("u2")).isEqualTo(new NotionRawService.NotionUser("Bot", null, true));
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("TTL 안에서는 auth별로 캐시를 재사용해 두 번째 호출이 HTTP를 다시 타지 않는다")
    void fetchAllUsers_cachesWithinTtl_secondCallSkipsHttp() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            return Mono.just(jsonResponse("{ \"results\": [], \"has_more\": false }"));
        });
        NotionRawService service = service(builder, Duration.ofMinutes(30));

        service.fetchAllUsers("Bearer t");
        service.fetchAllUsers("Bearer t");

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /v1/users 403은 삼키고 빈 맵을 반환한다 — capability 미설정으로 수집 전체가 막히면 안 된다")
    void fetchAllUsers_forbidden_returnsEmptyMapWithoutThrowing() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{ \"code\": \"restricted_resource\" }")
                        .build()));
        NotionRawService service = service(builder);

        Map<String, NotionRawService.NotionUser> users = service.fetchAllUsers("Bearer t");

        assertThat(users).isEmpty();
    }

    // ─── rate limit 재시도 (429·529) ────────────────────────────────────────

    @Test
    @DisplayName("429 응답에 Retry-After 헤더가 있으면 그 값을 따라 대기 후 재시도해 성공한다")
    void searchPages_429WithRetryAfterHeader_retriesAndSucceeds() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            if (callCount.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0")
                        .body("{}")
                        .build());
            }
            return Mono.just(jsonResponse("{ \"results\": [], \"has_more\": false }"));
        });
        NotionRawService service = service(builder, new NotionRateLimiter(0, 1, 5));

        NotionRawService.NotionSearchPageResult result = service.searchPages(
                new NotionRawService.NotionFetchContext("Bearer t", null), null);

        assertThat(result.pages()).isEmpty();
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Retry-After 헤더 없는 429는 지수 백오프로 대기 후 재시도한다")
    void searchPages_429WithoutHeader_usesBackoffAndRetries() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            if (callCount.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).body("{}").build());
            }
            return Mono.just(jsonResponse("{ \"results\": [], \"has_more\": false }"));
        });
        NotionRawService service = service(builder, new NotionRateLimiter(0, 1, 5));

        service.searchPages(new NotionRawService.NotionFetchContext("Bearer t", null), null);

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("529(overloaded)도 429와 동일하게 재시도 대상이다")
    void searchPages_529_retries() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            if (callCount.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(529)).body("{}").build());
            }
            return Mono.just(jsonResponse("{ \"results\": [], \"has_more\": false }"));
        });
        NotionRawService service = service(builder, new NotionRateLimiter(0, 1, 5));

        service.searchPages(new NotionRawService.NotionFetchContext("Bearer t", null), null);

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 상한(5회)을 넘기면 예외를 전파한다")
    void searchPages_exceedsMaxRetries_throws() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0")
                        .body("{}")
                        .build()));
        NotionRawService service = service(builder, new NotionRateLimiter(0, 1, 5));

        assertThatThrownBy(() -> service.searchPages(new NotionRawService.NotionFetchContext("Bearer t", null), null))
                .isInstanceOf(WebClientResponseException.class);
    }

    @Test
    @DisplayName("400 등 재시도 대상이 아닌 오류는 즉시 전파한다")
    void searchPages_nonRetryableError_throwsImmediately() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST).body("{}").build());
        });
        NotionRawService service = service(builder, new NotionRateLimiter(0, 1, 5));

        assertThatThrownBy(() -> service.searchPages(new NotionRawService.NotionFetchContext("Bearer t", null), null))
                .isInstanceOf(WebClientResponseException.class);
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private NotionRawService service(WebClient.Builder builder) {
        return service(builder, Duration.ofMinutes(30));
    }

    private NotionRawService service(WebClient.Builder builder, Duration userCacheTtl) {
        return new NotionRawService(builder, BASE_URL, VERSION, new NotionRateLimiter(0, 1, 5), userCacheTtl);
    }

    private NotionRawService service(WebClient.Builder builder, NotionRateLimiter rateLimiter) {
        return new NotionRawService(builder, BASE_URL, VERSION, rateLimiter, Duration.ofMinutes(30));
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build();
    }

    // ExchangeFunction은 이미 직렬화 완료된 ClientRequest만 받으므로, bodyValue(Map)로 만든 요청
    // 바디를 검증하려면 BodyInserter를 MockClientHttpRequest에 직접 써서 JSON 문자열로 복원해야
    // 한다(LinearRawServiceTest.captureRequestBody와 동일).
    private Map<String, Object> captureRequestBody(ClientRequest request) {
        MockClientHttpRequest httpRequest = new MockClientHttpRequest(request.method(), request.url());
        BodyInserter.Context context = new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Collections.emptyMap();
            }
        };
        request.body().insert(httpRequest, context).block();

        String json = httpRequest.getBodyAsString().block();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
