package com.history.pipeline_worker.source.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.dto.RawFetchRequest;
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
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JiraRawServiceTest {

    @Test
    @DisplayName("Jira projectKey는 허용된 패턴만 통과")
    void prepareFetchContext_invalidProjectKey_throwsException() {
        JiraRawService service = new JiraRawService(
                WebClient.builder(),
                "",
                50,
                new JiraRateLimiter(0)
        );
        RawFetchRequest request = new RawFetchRequest("Bearer token", "PROJ OR project=BAD",
                Map.of("baseUrl", "https://example.atlassian.net"));

        assertThatThrownBy(() -> service.prepareFetchContext(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Jira project key");
    }

    @Test
    @DisplayName("fetchSearchPage는 checkpoint 이후 updated 이슈만 남긴다")
    @SuppressWarnings("unchecked")
    void fetchSearchPage_filtersIssuesByUpdatedCheckpoint() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {
                                  "nextPageToken": "next-token",
                                  "issues": [
                                    {
                                      "key": "PROJ-1",
                                      "fields": {
                                        "updated": "2024-03-01T10:00:00.000+0900",
                                        "summary": "old"
                                      }
                                    },
                                    {
                                      "key": "PROJ-2",
                                      "fields": {
                                        "updated": "2024-03-03T10:00:00.000+0900",
                                        "summary": "new"
                                      }
                                    }
                                  ]
                                }
                                """)
                        .build()))
                .baseUrl("https://example.atlassian.net")
                .build();
        JiraRawService service = new JiraRawService(
                WebClient.builder(),
                "",
                50,
                new JiraRateLimiter(0)
        );
        JiraRawService.JiraFetchContext context = new JiraRawService.JiraFetchContext(
                client,
                "Bearer token",
                "PROJ",
                Instant.parse("2024-03-02T00:00:00Z")
        );

        JiraRawService.JiraSearchPage page = service.fetchSearchPage(context, null, 1);

        assertThat(page.nextPageToken()).isEqualTo("next-token");
        assertThat(page.limitReached()).isFalse();
        var issues = (java.util.List<Map<String, Object>>) page.searchResult().get("issues");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0)).containsEntry("key", "PROJ-2");
    }

    @Test
    @DisplayName("maxPagesPerRun을 초과하면 API 호출 없이 limitReached 페이지를 반환")
    void fetchSearchPage_overMaxPages_returnsLimitReached() {
        JiraRawService service = new JiraRawService(
                WebClient.builder(),
                "",
                1,
                new JiraRateLimiter(0)
        );
        JiraRawService.JiraFetchContext context = new JiraRawService.JiraFetchContext(
                WebClient.builder().baseUrl("https://example.atlassian.net").build(),
                "Bearer token",
                "PROJ",
                null
        );

        JiraRawService.JiraSearchPage page = service.fetchSearchPage(context, null, 2);

        assertThat(page.limitReached()).isTrue();
        assertThat(page.nextPageToken()).isNull();
    }

    @Test
    @DisplayName("검색 요청 바디의 fields 목록에 resolutiondate 포함 — closed_at 계산에 필요")
    @SuppressWarnings("unchecked")
    void fetchSearchPage_requestBody_includesResolutiondateField() {
        List<Map<String, Object>> capturedBodies = new ArrayList<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedBodies.add(captureRequestBody(request));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    { "issues": [] }
                                    """)
                            .build());
                })
                .baseUrl("https://example.atlassian.net")
                .build();
        JiraRawService service = new JiraRawService(
                WebClient.builder(),
                "",
                50,
                new JiraRateLimiter(0)
        );
        JiraRawService.JiraFetchContext context = new JiraRawService.JiraFetchContext(
                client,
                "Bearer token",
                "PROJ",
                null
        );

        service.fetchSearchPage(context, null, 1);

        assertThat(capturedBodies).hasSize(1);
        List<String> fields = (List<String>) capturedBodies.get(0).get("fields");
        assertThat(fields).contains("resolutiondate");
    }

    // ExchangeFunction은 이미 직렬화 완료된 ClientRequest만 받으므로, bodyValue(Map)로 만든
    // 요청 바디를 검증하려면 BodyInserter를 MockClientHttpRequest에 직접 써서 JSON 문자열로
    // 복원해야 한다.
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
