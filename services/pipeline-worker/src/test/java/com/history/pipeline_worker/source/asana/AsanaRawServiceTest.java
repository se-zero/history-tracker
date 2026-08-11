package com.history.pipeline_worker.source.asana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Asana REST 수집: GitHubRawServiceTest의 GET 쿼리 파라미터 검증 컨벤션
// (request.url().getPath()/getQuery())을 미러한다. Linear·Jira는 POST+JSON body를 쓰지만
// Asana는 GET+쿼리 파라미터라 바디 캡처(BodyInserter)가 필요 없다.
class AsanaRawServiceTest {

    private static final String BASE_URL = "https://app.asana.com/api/1.0";
    private static final String PROJECT_GID = "1200000000000001";

    @Test
    @DisplayName("GET /tasks 요청은 project·modified_since·limit=100·opt_fields 쿼리 파라미터와 Bearer 인증 헤더를 " +
            "담고, 1페이지에는 offset 파라미터가 없다")
    void fetchTaskPage_firstPageRequest_includesProjectSinceLimitOptFieldsAndNoOffset() {
        List<String> capturedPaths = new ArrayList<>();
        List<String> capturedQueries = new ArrayList<>();
        List<String> capturedAuthHeaders = new ArrayList<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedPaths.add(request.url().getPath());
                    capturedQueries.add(request.url().getQuery());
                    capturedAuthHeaders.add(request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    { "data": [], "next_page": null }
                                    """)
                            .build());
                })
                .baseUrl(BASE_URL)
                .build();
        AsanaRawService service = new AsanaRawService(WebClient.builder(), BASE_URL, 50, new AsanaRateLimiter(0));
        AsanaRawService.AsanaFetchContext context = new AsanaRawService.AsanaFetchContext(
                client, "Bearer asana-token", PROJECT_GID, Instant.parse("2024-03-02T00:00:00Z"));

        service.fetchTaskPage(context, null, 1);

        // baseUrl의 /api/1.0 서브패스에 상대 경로가 덧붙는 것이 WebClient의 병합 규칙이다 —
        // 실제 Asana 엔드포인트 도달 경로(/api/1.0/tasks) 전체를 단언한다.
        assertThat(capturedPaths).containsExactly("/api/1.0/tasks");
        String query = capturedQueries.get(0);
        assertThat(query)
                .contains("project=" + PROJECT_GID)
                .contains("modified_since=2024-03-02T00:00:00Z")
                .contains("limit=100")
                .contains("opt_fields=")
                .contains("name")
                .contains("notes")
                .contains("completed")
                .contains("created_by.email")
                .contains("assignee.email")
                .contains("parent")
                .doesNotContain("offset=");
        assertThat(capturedAuthHeaders).containsExactly("Bearer asana-token");
    }

    @Test
    @DisplayName("offset은 2페이지째부터만 쿼리 파라미터에 포함된다")
    void fetchTaskPage_secondPageRequest_includesOffsetParam() {
        List<String> capturedQueries = new ArrayList<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedQueries.add(request.url().getQuery());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    { "data": [], "next_page": null }
                                    """)
                            .build());
                })
                .baseUrl(BASE_URL)
                .build();
        AsanaRawService service = new AsanaRawService(WebClient.builder(), BASE_URL, 50, new AsanaRateLimiter(0));
        AsanaRawService.AsanaFetchContext context = new AsanaRawService.AsanaFetchContext(
                client, "Bearer asana-token", PROJECT_GID, Instant.parse("2024-03-02T00:00:00Z"));

        service.fetchTaskPage(context, "offset-token-1", 2);

        assertThat(capturedQueries.get(0)).contains("offset=offset-token-1");
    }

    @Test
    @DisplayName("next_page.offset이 있으면 hasNextPage=true, offset 토큰을 담는다")
    @SuppressWarnings("unchecked")
    void fetchTaskPage_nextPagePresent_hasNextPageTrueWithOffsetToken() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {
                                  "data": [ { "gid": "task-1", "name": "Fix bug" } ],
                                  "next_page": { "offset": "cursor-xyz" }
                                }
                                """)
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        AsanaRawService service = new AsanaRawService(WebClient.builder(), BASE_URL, 50, new AsanaRateLimiter(0));
        AsanaRawService.AsanaFetchContext context = new AsanaRawService.AsanaFetchContext(
                client, "Bearer asana-token", PROJECT_GID, null);

        AsanaRawService.AsanaTaskPage page = service.fetchTaskPage(context, null, 1);

        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.nextOffset()).isEqualTo("cursor-xyz");
        assertThat(page.limitReached()).isFalse();
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) page.body().get("data");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).containsEntry("gid", "task-1");
    }

    @Test
    @DisplayName("next_page가 null이면 마지막 페이지다 — hasNextPage=false, offset 토큰도 없다")
    void fetchTaskPage_nextPageNull_hasNextPageFalseNoOffsetToken() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                { "data": [], "next_page": null }
                                """)
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        AsanaRawService service = new AsanaRawService(WebClient.builder(), BASE_URL, 50, new AsanaRateLimiter(0));
        AsanaRawService.AsanaFetchContext context = new AsanaRawService.AsanaFetchContext(
                client, "Bearer asana-token", PROJECT_GID, null);

        AsanaRawService.AsanaTaskPage page = service.fetchTaskPage(context, null, 1);

        assertThat(page.hasNextPage()).isFalse();
        assertThat(page.nextOffset()).isNull();
    }

    @Test
    @DisplayName("maxPagesPerRun을 초과하면 API 호출 없이 limitReached 페이지를 반환한다")
    void fetchTaskPage_overMaxPages_returnsLimitReachedWithoutApiCall() {
        AsanaRawService service = new AsanaRawService(WebClient.builder(), BASE_URL, 1, new AsanaRateLimiter(0));
        AsanaRawService.AsanaFetchContext context = new AsanaRawService.AsanaFetchContext(
                WebClient.builder().baseUrl(BASE_URL).build(), "Bearer asana-token", PROJECT_GID, null);

        AsanaRawService.AsanaTaskPage page = service.fetchTaskPage(context, null, 2);

        assertThat(page.limitReached()).isTrue();
        assertThat(page.hasNextPage()).isFalse();
        assertThat(page.nextOffset()).isNull();
    }

    @Test
    @DisplayName("HTTP 오류 응답은 예외로 전파한다 — Asana는 REST라 상태코드로 오류를 신호한다 " +
            "(Linear GraphQL의 200 OK + errors 배열 패턴과 다르다)")
    void fetchTaskPage_httpError_throwsException() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{ \"errors\": [ { \"message\": \"Server Error\" } ] }")
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        AsanaRawService service = new AsanaRawService(WebClient.builder(), BASE_URL, 50, new AsanaRateLimiter(0));
        AsanaRawService.AsanaFetchContext context = new AsanaRawService.AsanaFetchContext(
                client, "Bearer asana-token", PROJECT_GID, null);

        assertThatThrownBy(() -> service.fetchTaskPage(context, null, 1))
                .isInstanceOf(WebClientResponseException.class);
    }
}
