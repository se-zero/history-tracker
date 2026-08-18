package com.history.pipeline_worker.source.notion;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
public class NotionRawService {

    public record NotionFetchContext(String auth, Instant checkpoint) {}
    public record NotionUser(String name, String email, boolean bot) {}
    public record NotionSearchPageResult(List<Map<String, Object>> pages, String nextCursor) {}

    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int BLOCK_PAGE_SIZE = 100;
    private static final int USERS_PAGE_SIZE = 100;

    // 무한 페이지 방어 상한 — docs/notion-integration.md §2-2.
    private static final int MAX_DEPTH = 5;
    private static final int MAX_BLOCKS_PER_PAGE = 2000;
    private static final int MAX_BODY_CHARS = 100_000;

    private static final int MAX_RETRY_ON_RATE_LIMIT = 5;

    private final WebClient webClient;
    private final NotionRateLimiter rateLimiter;
    private final String notionVersion;

    public NotionRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.notion.api-base-url}") String baseUrl,
            @Value("${app.notion.version}") String notionVersion,
            NotionRateLimiter rateLimiter
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.notionVersion = notionVersion;
        this.rateLimiter = rateLimiter;
    }

    public NotionFetchContext prepareFetchContext(RawFetchRequest request, Instant checkpoint) {
        return new NotionFetchContext(request.credentials(), checkpoint);
    }

    /**
     * POST /v1/search 한 페이지 — {@code last_edited_time} 내림차순. checkpoint보다 오래된
     * ({@code last_edited_time <= checkpoint}) 항목을 만나면 그 앞까지만 반환하고 다음 커서를
     * {@code null}로 끊는다(strict 비교 — 경계 항목의 무한 재발행을 막는다). search API에는
     * 시간 필터가 없어 이 조기 중단이 유일한 증분 수단이다(§5-2).
     */
    public NotionSearchPageResult searchPages(NotionFetchContext context, String cursor) {
        Map<String, Object> body = new HashMap<>();
        body.put("filter", Map.of("property", "object", "value", "page"));
        body.put("sort", Map.of("timestamp", "last_edited_time", "direction", "descending"));
        body.put("page_size", SEARCH_PAGE_SIZE);
        if (cursor != null) {
            body.put("start_cursor", cursor);
        }

        Map<String, Object> response = executeWithRateLimitRetry(() -> webClient.post()
                .uri("/search")
                .headers(headers -> applyAuth(headers, context.auth()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block());

        if (response == null) {
            return new NotionSearchPageResult(List.of(), null);
        }

        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> page : extractResults(response)) {
            Instant lastEdited = parseInstant(page.get("last_edited_time"));
            if (context.checkpoint() != null && lastEdited != null && !lastEdited.isAfter(context.checkpoint())) {
                return new NotionSearchPageResult(kept, null);
            }
            kept.add(page);
        }

        String nextCursor = Boolean.TRUE.equals(response.get("has_more"))
                ? (String) response.get("next_cursor")
                : null;
        return new NotionSearchPageResult(kept, nextCursor);
    }

    /**
     * 페이지 본문을 재귀 조회해 평문으로 접는다(§2-2). {@code child_page}·{@code child_database}는
     * 재귀하지 않는다 — 하위 페이지는 자기 차례에 독립 Document로 수집된다.
     */
    public String fetchPageBody(NotionFetchContext context, String pageId) {
        StringBuilder body = new StringBuilder();
        appendChildren(context, pageId, 1, body, new int[] {0});
        if (body.length() > MAX_BODY_CHARS) {
            log.warn("Notion 본문 상한({}자) 초과 — 잘라냄: pageId={} length={}", MAX_BODY_CHARS, pageId, body.length());
            return body.substring(0, MAX_BODY_CHARS);
        }
        return body.toString();
    }

    private void appendChildren(NotionFetchContext context, String blockId, int depth, StringBuilder body, int[] blockCount) {
        if (depth > MAX_DEPTH) {
            log.warn("Notion 블록 재귀 깊이 상한({}) 도달: blockId={}", MAX_DEPTH, blockId);
            return;
        }
        String cursor = null;
        do {
            if (blockCount[0] >= MAX_BLOCKS_PER_PAGE) {
                log.warn("Notion 블록 개수 상한({}) 도달: blockId={}", MAX_BLOCKS_PER_PAGE, blockId);
                return;
            }
            NotionBlockPage page = fetchBlockChildrenPage(context, blockId, cursor);
            for (Map<String, Object> block : page.blocks()) {
                if (blockCount[0] >= MAX_BLOCKS_PER_PAGE || body.length() > MAX_BODY_CHARS) {
                    return;
                }
                blockCount[0]++;
                String rendered = NotionBlockFlattener.render(block);
                if (!rendered.isBlank()) {
                    body.append(rendered).append('\n');
                }
                if (Boolean.TRUE.equals(block.get("has_children"))
                        && block.get("type") instanceof String type
                        && !NotionBlockFlattener.NON_RECURSING_TYPES.contains(type)
                        && block.get("id") instanceof String childId) {
                    appendChildren(context, childId, depth + 1, body, blockCount);
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    public record NotionBlockPage(List<Map<String, Object>> blocks, String nextCursor) {}

    private NotionBlockPage fetchBlockChildrenPage(NotionFetchContext context, String blockId, String cursor) {
        Map<String, Object> response = executeWithRateLimitRetry(() -> webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/blocks/{blockId}/children").queryParam("page_size", BLOCK_PAGE_SIZE);
                    if (cursor != null) {
                        uriBuilder.queryParam("start_cursor", cursor);
                    }
                    return uriBuilder.build(blockId);
                })
                .headers(headers -> applyAuth(headers, context.auth()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block());

        if (response == null) {
            return new NotionBlockPage(List.of(), null);
        }
        String nextCursor = Boolean.TRUE.equals(response.get("has_more")) ? (String) response.get("next_cursor") : null;
        return new NotionBlockPage(extractResults(response), nextCursor);
    }

    // 수집 실행마다 전량 재조회한다 — 프로세스 수명의 캐시는 연동 해제 후에도 구성원 이름·이메일이
    // 힙에 남는다. GET /v1/users는 조직 전체를 한 번에 내려주므로 구성원 100명당 요청 1회 수준이라
    // 실행마다 다시 불러도 비용이 무시할 만하다.
    public Map<String, NotionUser> fetchAllUsers(String auth) {
        Map<String, NotionUser> users = new HashMap<>();
        String cursor = null;
        do {
            String pageCursor = cursor;
            Map<String, Object> response;
            try {
                response = executeWithRateLimitRetry(() -> webClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path("/users").queryParam("page_size", USERS_PAGE_SIZE);
                            if (pageCursor != null) {
                                uriBuilder.queryParam("start_cursor", pageCursor);
                            }
                            return uriBuilder.build();
                        })
                        .headers(headers -> applyAuth(headers, auth))
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .block());
            } catch (WebClientResponseException.Forbidden exception) {
                // capability(user information) 꺼진 워크스페이스는 403이다. 여기서 전파하면
                // capability 설정 하나 때문에 수집 전체가 0건이 된다 — warn 후 빈 맵으로 계속한다.
                // 이미 채운 항목이 있어도 부분 맵은 오해를 줄 수 있으므로 통째로 비운다(§8).
                log.warn("Notion GET /v1/users 403 — 이름·이메일 없이 수집을 계속한다");
                return Map.of();
            }
            if (response == null) {
                break;
            }
            for (Map<String, Object> user : extractResults(response)) {
                if (user.get("id") instanceof String id) {
                    users.put(id, toNotionUser(user));
                }
            }
            cursor = Boolean.TRUE.equals(response.get("has_more")) ? (String) response.get("next_cursor") : null;
        } while (cursor != null);
        return users;
    }

    @SuppressWarnings("unchecked")
    private static NotionUser toNotionUser(Map<String, Object> user) {
        String name = user.get("name") instanceof String n ? n : null;
        boolean bot = "bot".equals(user.get("type"));
        String email = null;
        if (user.get("person") instanceof Map<?, ?> person
                && ((Map<String, Object>) person).get("email") instanceof String e) {
            email = e;
        }
        return new NotionUser(name, email, bot);
    }

    private void applyAuth(HttpHeaders headers, String auth) {
        headers.set(HttpHeaders.AUTHORIZATION, auth);
        headers.set("Notion-Version", notionVersion);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractResults(Map<String, Object> response) {
        if (!(response.get("results") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                results.add((Map<String, Object>) map);
            }
        }
        return results;
    }

    private static Instant parseInstant(Object value) {
        return value instanceof String text ? Instant.parse(text) : null;
    }

    // 429(rate limit)·529(overloaded) 모두 재시도 대상이다. Retry-After 헤더(초)가 있으면 그대로
    // 따르고, 없을 때만 지수 백오프로 대기한다 — Google Chat과 달리 Notion 서버는 대기 시간을
    // 알려주므로 헤더가 백오프보다 우선한다(§5-4).
    private <T> T executeWithRateLimitRetry(Supplier<T> request) {
        int attempts = 0;
        while (true) {
            try {
                T result = request.get();
                rateLimiter.afterRequest();
                return result;
            } catch (WebClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (status != 429 && status != 529) {
                    throw exception;
                }
                attempts++;
                if (attempts > MAX_RETRY_ON_RATE_LIMIT) {
                    throw exception;
                }
                Double retryAfterSeconds = parseRetryAfterHeader(exception);
                if (retryAfterSeconds != null) {
                    log.warn("Notion rate limit({}) — Retry-After {}초 대기 후 재시도 ({}/{})",
                            status, retryAfterSeconds, attempts, MAX_RETRY_ON_RATE_LIMIT);
                    rateLimiter.awaitRetryAfter(retryAfterSeconds);
                } else {
                    log.warn("Notion rate limit({}) — 지수 백오프 후 재시도 ({}/{})",
                            status, attempts, MAX_RETRY_ON_RATE_LIMIT);
                    rateLimiter.awaitBackoff(attempts);
                }
            }
        }
    }

    private static Double parseRetryAfterHeader(WebClientResponseException exception) {
        List<String> values = exception.getHeaders().get(HttpHeaders.RETRY_AFTER);
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(values.get(0));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
