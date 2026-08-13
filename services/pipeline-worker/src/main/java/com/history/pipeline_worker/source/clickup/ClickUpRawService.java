package com.history.pipeline_worker.source.clickup;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ClickUpRawService {

    public record ClickUpFetchContext(WebClient client, String auth, String workspaceId, String listId, Instant since) {}
    public record ClickUpTaskPage(Map<String, Object> body, boolean hasNextPage, boolean limitReached) {}

    private static final int PAGE_SIZE = 100;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final String baseUrl;
    private final int maxPagesPerRun;
    private final ClickUpRateLimiter rateLimiter;

    public ClickUpRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.clickup.base-url:https://api.clickup.com/api/v2}") String baseUrl,
            @Value("${app.clickup.max-pages-per-run:50}") int maxPagesPerRun,
            ClickUpRateLimiter rateLimiter
    ) {
        this.webClientBuilder = webClientBuilder;
        this.baseUrl = baseUrl;
        this.maxPagesPerRun = maxPagesPerRun;
        this.rateLimiter = rateLimiter;
    }

    // AsanaRawService.prepareFetchContext 미러 — 보유한 webClientBuilder/baseUrl로 WebClient를
    // 구성하고 auth/workspaceId/listId/since를 담은 ClickUpFetchContext를 반환한다.
    public ClickUpFetchContext prepareFetchContext(RawFetchRequest request, Instant since) {
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        String listId = request.options().get("list_id");
        return new ClickUpFetchContext(client, request.credentials(), request.projectKey(), listId, since);
    }

    // page는 offset 토큰이 아니라 0부터 시작하는 페이지 번호다.
    public ClickUpTaskPage fetchTaskPage(ClickUpFetchContext context, int page) {
        if (page >= maxPagesPerRun) {
            log.warn("ClickUp max pages per run 도달: maxPages={}", maxPagesPerRun);
            return new ClickUpTaskPage(Map.of("tasks", List.of()), false, true);
        }

        Map<String, Object> response = context.client().get()
                .uri(uriBuilder -> uriBuilder.path("/team/{workspaceId}/task")
                        .queryParam("page", page)
                        // ClickUp API에는 gte가 없고 gt만 있어 -1ms 보정으로 Jira updated>=/Linear updatedAt
                        // gte:since와 동일한 경계 포함(since와 같은 밀리초 갱신도 누락 없이 수집) 의미를 만든다.
                        .queryParam("date_updated_gt", context.since().toEpochMilli() - 1)
                        .queryParam("list_ids[]", context.listId())
                        .queryParam("include_closed", true)
                        .queryParam("subtasks", true)
                        .build(context.workspaceId()))
                .header("Authorization", context.auth())
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block();
        rateLimiter.acquire();

        if (response == null) {
            return new ClickUpTaskPage(Map.of("tasks", List.of()), false, false);
        }

        Object tasksValue = response.get("tasks");
        List<?> tasks = tasksValue instanceof List<?> list ? list : List.of();
        boolean hasNextPage = tasks.size() == PAGE_SIZE;

        return new ClickUpTaskPage(response, hasNextPage, false);
    }
}
