package com.history.pipeline_worker.source.googlechat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleChatRawServiceTest {

    private static final String SPACE_ID = "spaces/AAAA";

    @Test
    @DisplayName("스페이스 표시 이름을 GET /{spaceId}로 1회 조회한다 — spaceId의 슬래시가 %2F로 이중 인코딩되지 않는다")
    void fetchSpaceDisplayName_returnsDisplayNameField() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            // getPath()는 디코딩된 값이라 %2F 이중 인코딩 버그를 가려버린다(실제로 그렇게 놓쳤다) —
            // 반드시 getRawPath()로 실제 와이어에 나가는 문자열을 검증한다.
            assertThat(request.url().getRawPath()).isEqualTo("/v1/" + SPACE_ID);
            return Mono.just(jsonResponse("""
                    {"name": "spaces/AAAA", "displayName": "engineering"}
                    """));
        });
        GoogleChatRawService service = service(builder);

        String displayName = service.fetchSpaceDisplayName(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>()));

        assertThat(displayName).isEqualTo("engineering");
    }

    @Test
    @DisplayName("checkpoint가 없으면(초기 수집) filter 파라미터를 생략한다")
    void fetchMessages_withoutCheckpoint_omitsFilter() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            // spaceId의 슬래시가 %2F로 이중 인코딩되지 않는지도 함께 고정한다(getRawPath() 필수 — 이유는
            // fetchSpaceDisplayName 테스트 참고).
            assertThat(request.url().getRawPath()).isEqualTo("/v1/" + SPACE_ID + "/messages");
            Map<String, String> params = queryParams(request.url());
            assertThat(params).doesNotContainKey("filter");
            assertThat(params).containsEntry("orderBy", "createTime ASC");
            assertThat(params).containsEntry("pageSize", "1000");
            return Mono.just(jsonResponse("""
                    {"messages": []}
                    """));
        });
        GoogleChatRawService service = service(builder);

        service.fetchMessagePage(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>()),
                null);
    }

    @Test
    @DisplayName("checkpoint가 있으면 createTime > \"...\" strict 필터를 담는다")
    void fetchMessages_withCheckpoint_addsCreateTimeFilter() {
        Instant checkpoint = Instant.parse("2026-08-08T00:00:00Z");
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            Map<String, String> params = queryParams(request.url());
            assertThat(params).containsEntry("filter", "createTime > \"2026-08-08T00:00:00Z\"");
            return Mono.just(jsonResponse("""
                    {"messages": []}
                    """));
        });
        GoogleChatRawService service = service(builder);

        service.fetchMessagePage(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, checkpoint, new HashMap<>()),
                null);
    }

    @Test
    @DisplayName("nextPageToken을 그대로 돌려주고, 넘겨받은 pageToken을 요청에 담는다")
    void fetchMessagePage_returnsNextPageTokenAndSendsGivenPageToken() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            Map<String, String> params = queryParams(request.url());
            if (callCount.incrementAndGet() == 1) {
                assertThat(params).doesNotContainKey("pageToken");
                return Mono.just(jsonResponse("""
                        {"messages": [{"name": "spaces/AAAA/messages/1", "text": "hi"}], "nextPageToken": "page-2"}
                        """));
            }
            assertThat(params).containsEntry("pageToken", "page-2");
            return Mono.just(jsonResponse("""
                    {"messages": [{"name": "spaces/AAAA/messages/2", "text": "there"}]}
                    """));
        });
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        GoogleChatRawService.GoogleChatMessagePage first = service.fetchMessagePage(context, null);
        assertThat(first.messages()).extracting(m -> m.get("name"))
                .containsExactly("spaces/AAAA/messages/1");
        assertThat(first.nextPageToken()).isEqualTo("page-2");

        GoogleChatRawService.GoogleChatMessagePage second =
                service.fetchMessagePage(context, first.nextPageToken());
        assertThat(second.messages()).extracting(m -> m.get("name"))
                .containsExactly("spaces/AAAA/messages/2");
        assertThat(second.nextPageToken()).isNull();
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 페이지가 전부 노이즈여도 nextPageToken은 그대로 살아 페이지네이션이 멈추지 않는다")
    void fetchMessagePage_allNoisePage_stillCarriesNextPageToken() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                {
                  "messages": [
                    {"name": "m1", "text": "봇 메시지", "sender": {"type": "BOT"}},
                    {"name": "m2", "text": "삭제됨", "deletionMetadata": {"deletionType": "CREATOR"}}
                  ],
                  "nextPageToken": "page-2"
                }
                """)));
        GoogleChatRawService service = service(builder);

        GoogleChatRawService.GoogleChatMessagePage page = service.fetchMessagePage(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>()),
                null);

        assertThat(page.messages()).isEmpty();
        assertThat(page.nextPageToken()).isEqualTo("page-2");
    }

    @Test
    @DisplayName("봇 메시지·소프트 삭제 메시지·본문 없는 메시지는 제외한다")
    void fetchMessages_filtersNoise() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                {
                  "messages": [
                    {"name": "m1", "text": "사람 메시지"},
                    {"name": "m2", "text": "봇 메시지", "sender": {"type": "BOT"}},
                    {"name": "m3", "text": "삭제됨", "deletionMetadata": {"deletionType": "CREATOR"}},
                    {"name": "m4", "text": ""},
                    {"name": "m5"}
                  ]
                }
                """)));
        GoogleChatRawService service = service(builder);

        GoogleChatRawService.GoogleChatMessagePage page = service.fetchMessagePage(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>()),
                null);

        assertThat(page.messages()).extracting(m -> m.get("name")).containsExactly("m1");
    }

    @Test
    @DisplayName("429 응답이면 지수 백오프 후 재시도해 결국 성공한다")
    void fetchMessages_rateLimited_retriesAndSucceeds() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            if (callCount.incrementAndGet() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build());
            }
            return Mono.just(jsonResponse("""
                    {"messages": [{"name": "m1", "text": "재시도 성공"}]}
                    """));
        });
        GoogleChatRawService service = service(builder);

        GoogleChatRawService.GoogleChatMessagePage page = service.fetchMessagePage(
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>()),
                null);

        assertThat(page.messages()).hasSize(1);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("People API가 403(미설정 등)이면 빈 맵을 반환한다 — 이미 받아온 메시지를 폐기하지 않도록 예외를 던지지 않는다")
    void resolveSenders_forbidden_returnsEmptyMapInsteadOfThrowing() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(
                request -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build()));
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        Map<String, GoogleChatRawService.PersonInfo> result =
                service.resolveSenders(context, Set.of("users/U1"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("People API가 403이면 결과 맵에 남기지 않는다 — 같은 컨텍스트 안에서도 다음 호출이 같은 sender를 다시 조회한다")
    void resolveSenders_forbidden_doesNotCacheSoNextCallRetries() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
        });
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        service.resolveSenders(context, Set.of("users/U1"));
        service.resolveSenders(context, Set.of("users/U1"));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("People API가 500이어도 빈 맵을 반환한다 — 403 외 다른 오류 상태도 같은 규약을 따른다")
    void resolveSenders_internalServerError_returnsEmptyMapInsteadOfThrowing() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(
                request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()));
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        Map<String, GoogleChatRawService.PersonInfo> result =
                service.resolveSenders(context, Set.of("users/U1"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("429는 재시도 상한을 넘기면 여전히 예외로 전파한다 — 403·500과 달리 조용히 넘기지 않는다")
    void resolveSenders_rateLimitExhausted_stillThrows() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(
                request -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build()));
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        assertThatThrownBy(() -> service.resolveSenders(context, Set.of("users/U1")))
                .isInstanceOf(WebClientResponseException.TooManyRequests.class);
    }

    @Test
    @DisplayName("resolveSenders — people:batchGet에 users/{id}를 people/{id}로 바꿔 personFields와 함께 담는다")
    void resolveSenders_requestsBatchGetWithConvertedResourceNames() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getRawPath()).isEqualTo("/v1/people:batchGet");
            String rawQuery = request.url().getRawQuery();
            assertThat(rawQuery).contains("personFields=names,emailAddresses");
            assertThat(rawQuery).contains("resourceNames=people/U1");
            return Mono.just(jsonResponse("""
                    {
                      "responses": [
                        {
                          "requestedResourceName": "people/U1",
                          "person": {
                            "resourceName": "people/U1",
                            "names": [{"displayName": "Alice"}],
                            "emailAddresses": [{"value": "alice@example.com", "metadata": {"primary": true}}]
                          }
                        }
                      ]
                    }
                    """));
        });
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        Map<String, GoogleChatRawService.PersonInfo> result =
                service.resolveSenders(context, Set.of("users/U1"));

        assertThat(result).containsEntry("users/U1", new GoogleChatRawService.PersonInfo("Alice", "alice@example.com"));
    }

    @Test
    @DisplayName("이메일이 여러 개면 primary로 표시된 값을 쓰고, primary가 없으면 첫 번째로 폴백한다")
    void resolveSenders_prefersPrimaryEmailAmongMultiple() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse("""
                {
                  "responses": [
                    {
                      "requestedResourceName": "people/U1",
                      "person": {
                        "resourceName": "people/U1",
                        "names": [{"displayName": "Alice"}],
                        "emailAddresses": [
                          {"value": "secondary@example.com"},
                          {"value": "primary@example.com", "metadata": {"primary": true}}
                        ]
                      }
                    }
                  ]
                }
                """)));
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        Map<String, GoogleChatRawService.PersonInfo> result =
                service.resolveSenders(context, Set.of("users/U1"));

        assertThat(result.get("users/U1").email()).isEqualTo("primary@example.com");
    }

    @Test
    @DisplayName("같은 GoogleChatFetchContext 안에서는 두 번째 호출이 이미 조회한 sender를 재사용한다(People API 재호출 없음) — 실행 범위 재사용은 살아 있다")
    void resolveSenders_sameContext_reusesResolvedPerson() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            return Mono.just(jsonResponse("""
                    {
                      "responses": [
                        {
                          "requestedResourceName": "people/U1",
                          "person": {"resourceName": "people/U1", "names": [{"displayName": "Alice"}]}
                        }
                      ]
                    }
                    """));
        });
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        service.resolveSenders(context, Set.of("users/U1"));
        Map<String, GoogleChatRawService.PersonInfo> second = service.resolveSenders(context, Set.of("users/U1"));

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(second.get("users/U1").name()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("서로 다른 GoogleChatFetchContext(=서로 다른 수집 실행)로 같은 sender를 조회하면 people:batchGet이 실행마다 다시 호출된다 — 재사용이 실행 경계를 넘지 않는다")
    void resolveSenders_differentContexts_refetchSameSenderPerExecution() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            return Mono.just(jsonResponse("""
                    {
                      "responses": [
                        {
                          "requestedResourceName": "people/U1",
                          "person": {"resourceName": "people/U1", "names": [{"displayName": "Alice"}]}
                        }
                      ]
                    }
                    """));
        });
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext firstExecution =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());
        GoogleChatRawService.GoogleChatFetchContext secondExecution =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        service.resolveSenders(firstExecution, Set.of("users/U1"));
        service.resolveSenders(secondExecution, Set.of("users/U1"));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("응답에 없는 sender는 이번 결과에서 빠지고 맵에 남지 않는다 — 같은 컨텍스트 안에서도 다음 호출에서 재시도된다")
    void resolveSenders_missingPersonInResponse_skipsWithoutCaching() {
        AtomicInteger callCount = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            callCount.incrementAndGet();
            // U1만 응답에 포함되고 U2는 빠진 상황(조회 실패·프로필 비공개 등)을 흉내낸다
            return Mono.just(jsonResponse("""
                    {
                      "responses": [
                        {
                          "requestedResourceName": "people/U1",
                          "person": {"resourceName": "people/U1", "names": [{"displayName": "Alice"}]}
                        }
                      ]
                    }
                    """));
        });
        GoogleChatRawService service = service(builder);
        GoogleChatRawService.GoogleChatFetchContext context =
                new GoogleChatRawService.GoogleChatFetchContext("Bearer token", SPACE_ID, null, new HashMap<>());

        Map<String, GoogleChatRawService.PersonInfo> result =
                service.resolveSenders(context, Set.of("users/U1", "users/U2"));

        assertThat(result).containsKey("users/U1");
        assertThat(result).doesNotContainKey("users/U2");

        // 같은 컨텍스트 안에서도 U2가 다시 요청 대상에 포함되는지는 재호출 여부로 간접 확인한다
        service.resolveSenders(context, Set.of("users/U2"));
        assertThat(callCount.get()).isEqualTo(2);
    }

    private GoogleChatRawService service(WebClient.Builder builder) {
        return new GoogleChatRawService(
                builder, "https://chat.example/v1", "https://people.example/v1", rateLimiter());
    }

    private GoogleChatRateLimiter rateLimiter() {
        return new GoogleChatRateLimiter(0, 0, 0);
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build();
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }
}
