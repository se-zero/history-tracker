package com.history.pipeline_worker.source.googlechat;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
public class GoogleChatRawService {

    public record GoogleChatFetchContext(String auth, String spaceId, Instant lastScannedAt) {}

    // People API로 보강한 sender 이름·이메일. 사용자 인증으로는 Message.sender에 displayName이
    // 오지 않아(§ resolveSenders 참고) 별도 조회가 필요하다.
    public record PersonInfo(String name, String email) {}

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_RETRY_ON_RATE_LIMIT = 5;
    // people.getBatchGet 1회 호출당 최대 resourceNames 개수
    private static final int PEOPLE_BATCH_SIZE = 200;
    private static final String PERSON_FIELDS = "names,emailAddresses";

    private final WebClient webClient;
    private final WebClient peopleWebClient;
    private final GoogleChatRateLimiter rateLimiter;
    private final Duration personCacheTtl;

    // sender resource name("users/{id}") 단위 캐시 — 메시지에 등장한 만큼만 지연 조회한다.
    // Slack의 users.list처럼 조직 전체를 한 번에 내려주는 API가 없어(권한 범위상) 캐시 채우는
    // 방식이 다르다: 전체 선반입이 아니라 실제로 등장한 sender만 그때그때 채운다.
    private final Map<String, CachedPerson> personCache = new ConcurrentHashMap<>();

    private record CachedPerson(PersonInfo info, Instant fetchedAt) {}

    public GoogleChatRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.google-chat.api-base-url}") String baseUrl,
            @Value("${app.google-chat.people-api-base-url}") String peopleApiBaseUrl,
            GoogleChatRateLimiter rateLimiter,
            @Value("${app.google-chat.person-cache-ttl:30m}") Duration personCacheTtl
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        // 같은 builder를 재사용해도 안전하다 — build()는 그 시점 상태의 불변 WebClient를 찍어낼 뿐이라
        // 이후 baseUrl()을 다시 불러도 이미 만든 webClient에는 영향이 없다.
        this.peopleWebClient = webClientBuilder.baseUrl(peopleApiBaseUrl).build();
        this.rateLimiter = rateLimiter;
        this.personCacheTtl = personCacheTtl;
    }

    public GoogleChatFetchContext prepareFetchContext(RawFetchRequest request, Instant lastScannedAt) {
        return new GoogleChatFetchContext(request.credentials(), request.projectKey(), lastScannedAt);
    }

    // 스페이스 표시 이름을 매 수집마다 1회 조회한다 — external_ref.space_name 대신 최신 이름을 따라간다.
    public String fetchSpaceDisplayName(GoogleChatFetchContext context) {
        Map<String, Object> space = executeWithRateLimitRetry(() -> webClient.get()
                // spaceId 자체가 "spaces/{id}"로 슬래시를 포함한다 — {변수} 템플릿 치환에 넣으면
                // WebClient가 "하나의 경로 조각"으로 보고 슬래시까지 %2F로 인코딩해 404가 난다
                // (실측으로 발견). 문자열을 그대로 붙여 실제 경로 구분자로 취급되게 한다.
                .uri("/" + context.spaceId())
                .header("Authorization", context.auth())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block());
        Object displayName = space == null ? null : space.get("displayName");
        return displayName instanceof String name ? name : null;
    }

    /**
     * checkpoint 이후 메시지 전체를 수집한다. {@code filter=createTime > "{checkpoint}"}가 서버사이드로
     * strict 필터링해 주므로 Discord·Slack과 달리 클라이언트 쪽 경계 필터링이 필요 없다.
     * {@code orderBy=createTime ASC}라 이어받는 페이지의 순서도 안정적이다.
     */
    public List<Map<String, Object>> fetchMessages(GoogleChatFetchContext context) {
        List<Map<String, Object>> collected = new ArrayList<>();
        String pageToken = null;
        do {
            MessagesPage page = fetchMessagesPage(context, pageToken);
            collected.addAll(page.messages());
            pageToken = page.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return filterNoise(collected);
    }

    private record MessagesPage(List<Map<String, Object>> messages, String nextPageToken) {}

    @SuppressWarnings("unchecked")
    private MessagesPage fetchMessagesPage(GoogleChatFetchContext context, String pageToken) {
        String filter = filterExpression(context.lastScannedAt());
        Map<String, Object> response = executeWithRateLimitRetry(() -> webClient.get()
                .uri(uriBuilder -> {
                    // fetchSpaceDisplayName과 같은 이유로 {spaceId} 템플릿 치환 대신 문자열로 붙인다.
                    uriBuilder.path("/" + context.spaceId() + "/messages")
                            .queryParam("orderBy", "createTime ASC")
                            .queryParam("pageSize", PAGE_SIZE);
                    if (filter != null) {
                        uriBuilder.queryParam("filter", filter);
                    }
                    if (pageToken != null && !pageToken.isBlank()) {
                        uriBuilder.queryParam("pageToken", pageToken);
                    }
                    return uriBuilder.build();
                })
                .header("Authorization", context.auth())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block());

        if (response == null) {
            return new MessagesPage(List.of(), null);
        }
        Object rawMessages = response.get("messages");
        List<Map<String, Object>> messages = rawMessages instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        Object nextPageToken = response.get("nextPageToken");
        return new MessagesPage(messages, nextPageToken instanceof String token ? token : null);
    }

    // checkpoint가 없으면(초기 수집) filter를 아예 생략해 전체 히스토리를 대상으로 한다
    private static String filterExpression(Instant lastScannedAt) {
        if (lastScannedAt == null) {
            return null;
        }
        return "createTime > \"" + DateTimeFormatter.ISO_INSTANT.format(lastScannedAt) + "\"";
    }

    // 봇/앱 메시지, 소프트 삭제된 메시지, 본문 없는 메시지(카드·첨부만 있는 경우) 제외
    private static List<Map<String, Object>> filterNoise(List<Map<String, Object>> messages) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if (!isNoise(message)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private static boolean isNoise(Map<String, Object> message) {
        if (message.get("deletionMetadata") != null) {
            return true;
        }
        Map<String, Object> sender = (Map<String, Object>) message.get("sender");
        if (sender != null && "BOT".equals(sender.get("type"))) {
            return true;
        }
        Object text = message.get("text");
        return !(text instanceof String content) || content.isBlank();
    }

    /**
     * sender(users/{id})를 People API로 이름·이메일까지 보강한다. 실측 확인(2026-08-08): 사용자
     * 인증으로 Chat API를 쓰면 {@code Message.sender}에 {@code name}·{@code type}만 오고
     * {@code displayName}은 절대 오지 않는다(공식 문서 확인 — "the output for a User resource only
     * populates the user's name and type"). {@code users/{id}}는 People API의 {@code people/{id}}와
     * 동일 인물이라 여기서 별도로 풀어야 한다.
     *
     * <p>TTL 안에 캐시된 sender는 재호출하지 않는다. 새로 등장한 sender만
     * {@code people.getBatchGet}(최대 200개/호출)으로 한꺼번에 조회한다 — Slack의 user map과
     * 같은 목적(API 호출 수 절감)이지만, 조직 전체를 한 번에 내려주는 API가 없어(권한 범위상)
     * 메시지에 실제로 등장한 sender만 지연 조회하는 방식이 다르다.</p>
     */
    public Map<String, PersonInfo> resolveSenders(String auth, Set<String> senderResourceNames) {
        Map<String, PersonInfo> resolved = new HashMap<>();
        List<String> toFetch = new ArrayList<>();
        for (String senderName : senderResourceNames) {
            CachedPerson cached = personCache.get(senderName);
            if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(personCacheTtl) < 0) {
                resolved.put(senderName, cached.info());
            } else {
                toFetch.add(senderName);
            }
        }
        for (int i = 0; i < toFetch.size(); i += PEOPLE_BATCH_SIZE) {
            List<String> batch = toFetch.subList(i, Math.min(i + PEOPLE_BATCH_SIZE, toFetch.size()));
            resolved.putAll(fetchPersonBatch(auth, batch));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, PersonInfo> fetchPersonBatch(String auth, List<String> senderResourceNames) {
        Map<String, PersonInfo> result = new HashMap<>();
        Map<String, Object> response;
        try {
            response = executeWithRateLimitRetry(() -> peopleWebClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/people:batchGet").queryParam("personFields", PERSON_FIELDS);
                        for (String senderName : senderResourceNames) {
                            uriBuilder.queryParam("resourceNames", toPersonResourceName(senderName));
                        }
                        return uriBuilder.build();
                    })
                    .header("Authorization", auth)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block());
        } catch (WebClientResponseException exception) {
            if (exception instanceof WebClientResponseException.TooManyRequests) {
                // executeWithRateLimitRetry가 재시도 상한까지 소진한 뒤에도 실패한 경우 — 지속적인
                // rate limit은 조용히 넘기지 않고 그대로 전파한다
                throw exception;
            }
            // People API는 Cloud Console에서 별도 활성화가 필요해 미설정 환경에서 403이 흔하다.
            // 여기서 전파하면 이미 fetchMessages로 받아온 메시지 전체가 collect()에서 폐기되므로,
            // §7의 "조회 실패 sender는 그 실행만 null, 캐시하지 않음" 규약을 HTTP 오류 경로에도
            // 그대로 적용해 이름·이메일 없이 수집을 이어간다(다음 실행에서 재시도된다).
            log.warn("Google Chat People API 호출 실패({}) — 이번 배치는 이름·이메일 없이 진행: batchSize={}",
                    exception.getStatusCode(), senderResourceNames.size());
            return result;
        }

        if (response == null || !(response.get("responses") instanceof List<?> responses)) {
            return result;
        }
        for (Object rawEntry : responses) {
            Map<String, Object> entry = (Map<String, Object>) rawEntry;
            Map<String, Object> person = (Map<String, Object>) entry.get("person");
            // requestedResourceName이 없는 응답 형태에도 대비해 person.resourceName으로 폴백한다
            String personResourceName = entry.get("requestedResourceName") instanceof String requested
                    ? requested
                    : (person != null ? (String) person.get("resourceName") : null);
            if (personResourceName == null || person == null) {
                log.warn("Google Chat People API 조회 실패 — 이번 실행에서는 건너뛴다(다음 실행에서 재시도): entry={}", entry);
                continue;
            }
            String senderName = toUserResourceName(personResourceName);
            PersonInfo info = new PersonInfo(extractDisplayName(person), extractPrimaryEmail(person));
            personCache.put(senderName, new CachedPerson(info, Instant.now()));
            result.put(senderName, info);
        }
        return result;
    }

    private static String toPersonResourceName(String userResourceName) {
        return userResourceName.startsWith("users/")
                ? "people/" + userResourceName.substring("users/".length())
                : userResourceName;
    }

    private static String toUserResourceName(String personResourceName) {
        return personResourceName.startsWith("people/")
                ? "users/" + personResourceName.substring("people/".length())
                : personResourceName;
    }

    @SuppressWarnings("unchecked")
    private static String extractDisplayName(Map<String, Object> person) {
        if (!(person.get("names") instanceof List<?> names) || names.isEmpty()) {
            return null;
        }
        Map<String, Object> first = (Map<String, Object>) names.get(0);
        return first.get("displayName") instanceof String name ? name : null;
    }

    // primary로 표시된 이메일을 우선하고, 없으면 첫 번째 항목으로 폴백한다
    @SuppressWarnings("unchecked")
    private static String extractPrimaryEmail(Map<String, Object> person) {
        if (!(person.get("emailAddresses") instanceof List<?> emails) || emails.isEmpty()) {
            return null;
        }
        for (Object rawEmail : emails) {
            Map<String, Object> email = (Map<String, Object>) rawEmail;
            Object metadata = email.get("metadata");
            if (metadata instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("primary"))) {
                return email.get("value") instanceof String value ? value : null;
            }
        }
        Map<String, Object> first = (Map<String, Object>) emails.get(0);
        return first.get("value") instanceof String value ? value : null;
    }

    private <T> T executeWithRateLimitRetry(Supplier<T> request) {
        int attempts = 0;
        while (true) {
            try {
                T result = request.get();
                rateLimiter.afterRequest();
                return result;
            } catch (WebClientResponseException.TooManyRequests exception) {
                attempts++;
                if (attempts > MAX_RETRY_ON_RATE_LIMIT) {
                    throw exception;
                }
                log.warn("Google Chat rate limit(429) — 지수 백오프 후 재시도 ({}/{})", attempts, MAX_RETRY_ON_RATE_LIMIT);
                rateLimiter.awaitRetry(attempts);
            }
        }
    }
}
