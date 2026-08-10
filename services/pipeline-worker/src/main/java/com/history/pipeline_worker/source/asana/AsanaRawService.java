package com.history.pipeline_worker.source.asana;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AsanaRawService {

    public record AsanaFetchContext(WebClient client, String auth, String projectGid, Instant since) {}
    public record AsanaTaskPage(Map<String, Object> body, boolean hasNextPage, String nextOffset, boolean limitReached) {}

    // opt_fields는 응답에 포함할 필드를 명시적으로 요청한다 — Asana는 기본적으로 최소 필드만 반환한다.
    private static final String OPT_FIELDS = "name,notes,completed,completed_at,created_at,modified_at,"
            + "created_by.name,created_by.email,assignee.name,assignee.email,parent";
    private static final int PAGE_SIZE = 100;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final String baseUrl;
    private final int maxPagesPerRun;
    private final AsanaRateLimiter rateLimiter;

    public AsanaRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.asana.base-url:https://app.asana.com/api/1.0}") String baseUrl,
            @Value("${app.asana.max-pages-per-run:50}") int maxPagesPerRun,
            AsanaRateLimiter rateLimiter
    ) {
        this.webClientBuilder = webClientBuilder;
        this.baseUrl = baseUrl;
        this.maxPagesPerRun = maxPagesPerRun;
        this.rateLimiter = rateLimiter;
    }

    // LinearRawService.prepareFetchContext 미러 — 보유한 webClientBuilder/baseUrl로 WebClient를
    // 구성하고 auth/projectGid/since를 담은 AsanaFetchContext를 반환한다.
    public AsanaFetchContext prepareFetchContext(RawFetchRequest request, Instant since) {
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        return new AsanaFetchContext(client, request.credentials(), request.projectKey(), since);
    }

    public AsanaTaskPage fetchTaskPage(AsanaFetchContext context, String offset, int pageNumber) {
        if (pageNumber > maxPagesPerRun) {
            log.warn("Asana max pages per run 도달: maxPages={}", maxPagesPerRun);
            return new AsanaTaskPage(Map.of("data", List.of()), false, null, true);
        }

        Map<String, Object> response = context.client().get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/tasks")
                            .queryParam("project", context.projectGid())
                            .queryParam("limit", PAGE_SIZE)
                            .queryParam("opt_fields", OPT_FIELDS);
                    if (context.since() != null) {
                        uriBuilder.queryParam("modified_since", context.since().toString());
                    }
                    if (offset != null) {
                        uriBuilder.queryParam("offset", offset);
                    }
                    return uriBuilder.build();
                })
                .header("Authorization", context.auth())
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block();
        rateLimiter.acquire();

        if (response == null) {
            return new AsanaTaskPage(Map.of("data", List.of()), false, null, false);
        }

        Map<String, Object> nextPage = toStringObjectMap(response.get("next_page"));
        boolean hasNextPage = !nextPage.isEmpty() && nextPage.get("offset") != null;
        String nextOffset = hasNextPage ? (String) nextPage.get("offset") : null;

        return new AsanaTaskPage(response, hasNextPage, nextOffset, false);
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
