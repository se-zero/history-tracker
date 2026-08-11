package com.history.pipeline_worker.source.linear;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Linear GraphQL 수집: JiraRawServiceTest의 exchangeFunction 스텁 컨벤션을 미러한다.
// Linear는 단일 글로벌 엔드포인트(https://api.linear.app/graphql)를 쓰므로, Jira처럼
// 요청별 baseUrl 옵션이 필요 없다 — 생성자에 고정 baseUrl을 준다.
class LinearRawServiceTest {

    private static final String BASE_URL = "https://api.linear.app";

    @Test
    @DisplayName("GraphQL 요청은 /graphql로 Bearer 인증 헤더를 붙여 POST하고, "
            + "body에 team id·updatedAt 필터(since)·after 커서를 담는다")
    @SuppressWarnings("unchecked")
    void fetchIssuePage_requestBody_includesTeamIdSinceFilterAndAfterCursor() {
        List<Map<String, Object>> capturedBodies = new ArrayList<>();
        List<String> capturedUris = new ArrayList<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedUris.add(request.url().toString());
                    capturedBodies.add(captureRequestBody(request));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    {
                                      "data": {
                                        "team": {
                                          "issues": {
                                            "nodes": [],
                                            "pageInfo": { "hasNextPage": false, "endCursor": null }
                                          }
                                        }
                                      }
                                    }
                                    """)
                            .build());
                })
                .baseUrl(BASE_URL)
                .build();
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 50, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                client, "Bearer linear-token", "TEAM-1", Instant.parse("2024-03-02T00:00:00Z"));

        service.fetchIssuePage(context, "cursor-abc", 1);

        assertThat(capturedUris).hasSize(1);
        assertThat(capturedUris.get(0)).isEqualTo(BASE_URL + "/graphql");
        Map<String, Object> body = capturedBodies.get(0);
        assertThat(body.get("query")).asString().contains("team(id: $teamId)");
        Map<String, Object> variables = (Map<String, Object>) body.get("variables");
        assertThat(variables).containsEntry("teamId", "TEAM-1");
        assertThat(variables).containsEntry("after", "cursor-abc");
        assertThat(variables).containsEntry("since", "2024-03-02T00:00:00Z");
    }

    @Test
    @DisplayName("Bearer 인증 헤더가 AuthHeaders 컨벤션대로 Authorization에 실린다")
    void fetchIssuePage_setsAuthorizationHeader() {
        List<String> capturedAuthHeaders = new ArrayList<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedAuthHeaders.add(request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    { "data": { "team": { "issues": {
                                        "nodes": [], "pageInfo": { "hasNextPage": false, "endCursor": null } } } } }
                                    """)
                            .build());
                })
                .baseUrl(BASE_URL)
                .build();
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 50, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                client, "Bearer linear-token", "TEAM-1", null);

        service.fetchIssuePage(context, null, 1);

        assertThat(capturedAuthHeaders).containsExactly("Bearer linear-token");
    }

    @Test
    @DisplayName("nodes를 issues 리스트로, pageInfo를 hasNextPage/endCursor로 파싱한다")
    @SuppressWarnings("unchecked")
    void fetchIssuePage_parsesNodesAndPageInfo() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {
                                  "data": {
                                    "team": {
                                      "issues": {
                                        "nodes": [
                                          { "id": "issue-1", "title": "Fix bug", "updatedAt": "2024-03-03T10:00:00.000Z" }
                                        ],
                                        "pageInfo": { "hasNextPage": true, "endCursor": "cursor-xyz" }
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 50, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                client, "Bearer linear-token", "TEAM-1", null);

        LinearRawService.LinearIssuePage page = service.fetchIssuePage(context, null, 1);

        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.endCursor()).isEqualTo("cursor-xyz");
        assertThat(page.limitReached()).isFalse();
        List<Map<String, Object>> issues = (List<Map<String, Object>>) page.searchResult().get("issues");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0)).containsEntry("id", "issue-1");
    }

    @Test
    @DisplayName("200 응답이라도 errors 배열이 비어 있지 않으면 data가 있어도 전체를 예외로 거부한다 "
            + "— 부분 데이터를 소비하고 커서를 전진시키면 영구 누락이 생긴다")
    void fetchIssuePage_httpOkWithNonEmptyErrors_throwsEvenWhenDataPresent() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {
                                  "errors": [ { "message": "Team not found" } ],
                                  "data": {
                                    "team": {
                                      "issues": {
                                        "nodes": [ { "id": "issue-1" } ],
                                        "pageInfo": { "hasNextPage": false, "endCursor": null }
                                      }
                                    }
                                  }
                                }
                                """)
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 50, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                client, "Bearer linear-token", "TEAM-1", null);

        assertThatThrownBy(() -> service.fetchIssuePage(context, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Linear GraphQL");
    }

    @Test
    @DisplayName("errors 배열이 없으면(혹은 비어 있으면) 정상적으로 파싱한다")
    void fetchIssuePage_emptyOrMissingErrors_parsesNormally() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {
                                  "errors": [],
                                  "data": { "team": { "issues": {
                                      "nodes": [], "pageInfo": { "hasNextPage": false, "endCursor": null } } } }
                                }
                                """)
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 50, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                client, "Bearer linear-token", "TEAM-1", null);

        LinearRawService.LinearIssuePage page = service.fetchIssuePage(context, null, 1);

        assertThat(page.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName("HTTP 오류 응답은 예외로 전파한다")
    void fetchIssuePage_httpError_throwsException() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{ \"message\": \"internal error\" }")
                        .build()))
                .baseUrl(BASE_URL)
                .build();
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 50, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                client, "Bearer linear-token", "TEAM-1", null);

        assertThatThrownBy(() -> service.fetchIssuePage(context, null, 1))
                .isInstanceOf(WebClientResponseException.class);
    }

    @Test
    @DisplayName("maxPagesPerRun을 초과하면 API 호출 없이 limitReached 페이지를 반환한다")
    void fetchIssuePage_overMaxPages_returnsLimitReachedWithoutApiCall() {
        LinearRawService service = new LinearRawService(WebClient.builder(), BASE_URL, 1, new LinearRateLimiter(0));
        LinearRawService.LinearFetchContext context = new LinearRawService.LinearFetchContext(
                WebClient.builder().baseUrl(BASE_URL).build(), "Bearer linear-token", "TEAM-1", null);

        LinearRawService.LinearIssuePage page = service.fetchIssuePage(context, null, 2);

        assertThat(page.limitReached()).isTrue();
        assertThat(page.hasNextPage()).isFalse();
        assertThat(page.endCursor()).isNull();
    }

    // ExchangeFunction은 이미 직렬화 완료된 ClientRequest만 받으므로, bodyValue(Map)로 만든
    // 요청 바디를 검증하려면 BodyInserter를 MockClientHttpRequest에 직접 써서 JSON 문자열로
    // 복원해야 한다. (JiraRawServiceTest.captureRequestBody와 동일)
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
