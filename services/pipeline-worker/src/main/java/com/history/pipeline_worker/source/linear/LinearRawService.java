package com.history.pipeline_worker.source.linear;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LinearRawService {

    public record LinearIssuePage(Map<String, Object> searchResult, boolean hasNextPage,
                                   String endCursor, boolean limitReached) {}
    public record LinearFetchContext(WebClient client, String auth, String teamId, Instant since) {}

    // team(id: $teamId) 아래 issues만 요청한다 — 팀 스코프 연동(문서 issue-source-expansion.md
    // 문제 7)이라 워크스페이스 전체를 조회할 필요가 없다.
    private static final String ISSUES_QUERY = """
            query IssuesByTeam($teamId: String!, $after: String, $since: DateTimeOrDuration) {
              team(id: $teamId) {
                issues(
                  first: 50
                  after: $after
                  orderBy: updatedAt
                  filter: { updatedAt: { gte: $since } }
                ) {
                  nodes {
                    id
                    identifier
                    title
                    description
                    priority
                    priorityLabel
                    url
                    createdAt
                    updatedAt
                    completedAt
                    canceledAt
                    state {
                      name
                      type
                    }
                    assignee {
                      id
                      name
                      email
                      app
                    }
                    creator {
                      id
                      name
                      email
                      app
                    }
                    botActor {
                      id
                      name
                      type
                    }
                    labels {
                      nodes {
                        name
                      }
                    }
                    parent {
                      id
                      identifier
                    }
                    team {
                      id
                    }
                  }
                  pageInfo {
                    hasNextPage
                    endCursor
                  }
                }
              }
            }
            """;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final String baseUrl;
    private final int maxPagesPerRun;
    private final LinearRateLimiter rateLimiter;

    public LinearRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.linear.base-url:https://api.linear.app}") String baseUrl,
            @Value("${app.linear.max-pages-per-run:50}") int maxPagesPerRun,
            LinearRateLimiter rateLimiter
    ) {
        this.webClientBuilder = webClientBuilder;
        this.baseUrl = baseUrl;
        this.maxPagesPerRun = maxPagesPerRun;
        this.rateLimiter = rateLimiter;
    }

    // JiraRawService.prepareFetchContext 미러 — 보유한 webClientBuilder/baseUrl로 WebClient를
    // 구성하고 auth/teamId/since를 담은 LinearFetchContext를 반환한다.
    public LinearFetchContext prepareFetchContext(RawFetchRequest request, Instant since) {
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        return new LinearFetchContext(client, request.credentials(), request.projectKey(), since);
    }

    public LinearIssuePage fetchIssuePage(LinearFetchContext context, String after, int pageNumber) {
        if (pageNumber > maxPagesPerRun) {
            log.warn("Linear max pages per run 도달: maxPages={}", maxPagesPerRun);
            return new LinearIssuePage(Map.of("issues", List.of()), false, null, true);
        }

        Map<String, Object> response = context.client().post()
                .uri("/graphql")
                .header("Authorization", context.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json")
                .bodyValue(requestBody(context, after))
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block();
        rateLimiter.acquire();

        rejectIfErrors(response);
        if (response == null) {
            return new LinearIssuePage(Map.of("issues", List.of()), false, null, false);
        }

        Map<String, Object> issuesNode = toStringObjectMap(
                toStringObjectMap(toStringObjectMap(response.get("data")).get("team")).get("issues"));
        Map<String, Object> pageInfo = toStringObjectMap(issuesNode.get("pageInfo"));
        List<Object> nodes = issuesNode.get("nodes") instanceof List<?> list ? List.copyOf(list) : List.of();

        boolean hasNextPage = Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
        String endCursor = (String) pageInfo.get("endCursor");

        return new LinearIssuePage(Map.of("issues", nodes), hasNextPage, endCursor, false);
    }

    private Map<String, Object> requestBody(LinearFetchContext context, String after) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("teamId", context.teamId());
        variables.put("after", after);
        variables.put("since", context.since() != null ? context.since().toString() : null);

        Map<String, Object> body = new HashMap<>();
        body.put("query", ISSUES_QUERY);
        body.put("variables", variables);
        return body;
    }

    // 200 응답이라도 errors가 비어있지 않으면 data가 있어도 전체를 거부한다 — 부분 데이터를 소비하고
    // 커서를 전진시키면 영구 누락이 생긴다.
    private void rejectIfErrors(Map<String, Object> response) {
        if (response == null) return;
        Object errors = response.get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
            throw new IllegalStateException("Linear GraphQL 응답에 errors가 포함됨: " + errorList);
        }
    }

    private Map<String, Object> toStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) return Map.of();
        Map<String, Object> result = new HashMap<>();
        rawMap.forEach((key, mapValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, mapValue);
            }
        });
        return result;
    }
}
