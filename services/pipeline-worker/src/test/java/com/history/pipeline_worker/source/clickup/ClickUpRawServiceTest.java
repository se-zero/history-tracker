package com.history.pipeline_worker.source.clickup;

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

// ClickUp REST 수집: AsanaRawServiceTest의 GET 쿼리 파라미터 검증 컨벤션
// (request.url().getPath()/getQuery())을 미러한다. Asana는 offset 토큰으로 페이지네이션하지만
// ClickUp은 0부터 시작하는 page 번호 그 자체가 페이지네이션 상태라 offset 캡처가 필요 없다.
class ClickUpRawServiceTest {

    private static final String BASE_URL = "https://api.clickup.com/api/v2";
    private static final String WORKSPACE_ID = "4567890";
    private static final String LIST_ID = "123456789";

    @Test
    @DisplayName("1페이지(page=0) 요청은 base-url 서브패스를 포함한 전체 경로(/api/v2/team/{workspace_id}/task)와 " +
            "date_updated_gt·list_ids[]·include_closed=true·subtasks=true 쿼리, Bearer 인증 헤더를 담는다")
    void fetchTaskPage_firstPageRequest_includesFullPathAndQueryParamsAndAuthHeader() {
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
                                    { "tasks": [] }
                                    """)
                            .build());
                })
                .baseUrl(BASE_URL)
                .build();
        ClickUpRawService service = new ClickUpRawService(WebClient.builder(), BASE_URL, 50, new ClickUpRateLimiter(0));
        Instant since = Instant.parse("2024-03-02T00:00:00Z");
        ClickUpRawService.ClickUpFetchContext context = new ClickUpRawService.ClickUpFetchContext(
                client, "Bearer clickup-token", WORKSPACE_ID, LIST_ID, since);

        service.fetchTaskPage(context, 0);

        // baseUrl의 /api/v2 서브패스에 상대 경로가 덧붙는 것이 WebClient의 병합 규칙이다 —
        // 실제 ClickUp 엔드포인트 도달 경로(/api/v2/team/{workspace_id}/task) 전체를 단언한다.
        assertThat(capturedPaths).containsExactly("/api/v2/team/" + WORKSPACE_ID + "/task");
        String query = capturedQueries.get(0);
        assertThat(query)
                .contains("page=0")
                .contains("date_updated_gt=" + since.toEpochMilli())
                .contains("list_ids[]=" + LIST_ID)
                .contains("include_closed=true")
                .contains("subtasks=true");
        assertThat(capturedAuthHeaders).containsExactly("Bearer clickup-token");
    }

    @Test
    @DisplayName("2페이지 요청은 page=1 쿼리 파라미터를 담는다")
    void fetchTaskPage_secondPageRequest_includesPageOne() {
        List<String> capturedQueries = new ArrayList<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedQueries.add(request.url().getQuery());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    { "tasks": [] }
                                    """)
                            .build());
                })
                .baseUrl(BASE_URL)
                .build();
        ClickUpRawService service = new ClickUpRawService(WebClient.builder(), BASE_URL, 50, new ClickUpRateLimiter(0));
        ClickUpRawService.ClickUpFetchContext context = new ClickUpRawService.ClickUpFetchContext(
                client, "Bearer clickup-token", WORKSPACE_ID, LIST_ID, Instant.parse("2024-03-02T00:00:00Z"));

        service.fetchTaskPage(context, 1);

        assertThat(capturedQueries.get(0)).contains("page=1");
    }

    @Test
    @DisplayName("태스크가 100건이면 hasNextPage=true")
    void fetchTaskPage_100Tasks_hasNextPageTrue() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(taskListBody(100))
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        ClickUpRawService service = new ClickUpRawService(WebClient.builder(), BASE_URL, 50, new ClickUpRateLimiter(0));
        ClickUpRawService.ClickUpFetchContext context = new ClickUpRawService.ClickUpFetchContext(
                client, "Bearer clickup-token", WORKSPACE_ID, LIST_ID, Instant.EPOCH);

        ClickUpRawService.ClickUpTaskPage page = service.fetchTaskPage(context, 0);

        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.limitReached()).isFalse();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) page.body().get("tasks");
        assertThat(tasks).hasSize(100);
    }

    @Test
    @DisplayName("태스크가 100건 미만이면 마지막 페이지다 — hasNextPage=false")
    void fetchTaskPage_lessThan100Tasks_hasNextPageFalse() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(taskListBody(1))
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        ClickUpRawService service = new ClickUpRawService(WebClient.builder(), BASE_URL, 50, new ClickUpRateLimiter(0));
        ClickUpRawService.ClickUpFetchContext context = new ClickUpRawService.ClickUpFetchContext(
                client, "Bearer clickup-token", WORKSPACE_ID, LIST_ID, Instant.EPOCH);

        ClickUpRawService.ClickUpTaskPage page = service.fetchTaskPage(context, 0);

        assertThat(page.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName("maxPagesPerRun을 초과하면 API 호출 없이 limitReached 페이지를 반환한다")
    void fetchTaskPage_overMaxPages_returnsLimitReachedWithoutApiCall() {
        ClickUpRawService service = new ClickUpRawService(WebClient.builder(), BASE_URL, 1, new ClickUpRateLimiter(0));
        ClickUpRawService.ClickUpFetchContext context = new ClickUpRawService.ClickUpFetchContext(
                WebClient.builder().baseUrl(BASE_URL).build(), "Bearer clickup-token", WORKSPACE_ID, LIST_ID, Instant.EPOCH);

        ClickUpRawService.ClickUpTaskPage page = service.fetchTaskPage(context, 1);

        assertThat(page.limitReached()).isTrue();
        assertThat(page.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName("HTTP 오류 응답은 예외로 전파한다 — ClickUp은 REST라 상태코드로 오류를 신호한다")
    void fetchTaskPage_httpError_throwsException() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{ \"err\": \"Server Error\" }")
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        ClickUpRawService service = new ClickUpRawService(WebClient.builder(), BASE_URL, 50, new ClickUpRateLimiter(0));
        ClickUpRawService.ClickUpFetchContext context = new ClickUpRawService.ClickUpFetchContext(
                client, "Bearer clickup-token", WORKSPACE_ID, LIST_ID, Instant.EPOCH);

        assertThatThrownBy(() -> service.fetchTaskPage(context, 0))
                .isInstanceOf(WebClientResponseException.class);
    }

    private String taskListBody(int count) {
        StringBuilder tasks = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) tasks.append(",");
            tasks.append("{ \"id\": \"task-").append(i).append("\" }");
        }
        return "{ \"tasks\": [" + tasks + "] }";
    }
}
