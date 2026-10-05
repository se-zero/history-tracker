package com.history.backend.oauth.service;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// CIMD 문서를 fetch·검증·캐싱한다. 실패를 Invalid(영구 무효)/Unavailable(일시 장애) 둘로 나누는
// 이유는, 무효한 문서는 즉시 클라이언트 인식을 끊어야 하고 일시 장애는 이미 저장된 그림자 행으로
// 버텨야 하기 때문이다(CimdRegisteredClientRepository가 이 구분으로 분기한다).
// 기본 5분 캐시는 인가→토큰 발급→refresh가 수 초 안에 문서를 세 번 조회할 수 있어서다.
@Component
public class CimdDocumentFetcher {

    private static final int MAX_BODY_BYTES = 65_536;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_MAX_AGE = Duration.ofHours(24);
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 1_000;
    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age=(\\d+)");

    private final RestClient restClient;
    private final SafeUrlValidator urlValidator;
    private final McpRegisteredClientPolicy policy;
    private final Clock clock;
    private final int maxCacheEntries;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public CimdDocumentFetcher(
            @Qualifier("cimdRestClient") RestClient restClient, SafeUrlValidator urlValidator, McpRegisteredClientPolicy policy) {
        this(restClient, urlValidator, policy, Clock.systemUTC());
    }

    CimdDocumentFetcher(RestClient restClient, SafeUrlValidator urlValidator, McpRegisteredClientPolicy policy, Clock clock) {
        this(restClient, urlValidator, policy, clock, DEFAULT_MAX_CACHE_ENTRIES);
    }

    CimdDocumentFetcher(
            RestClient restClient, SafeUrlValidator urlValidator, McpRegisteredClientPolicy policy, Clock clock, int maxCacheEntries) {
        this.restClient = restClient;
        this.urlValidator = urlValidator;
        this.policy = policy;
        this.clock = clock;
        this.maxCacheEntries = maxCacheEntries;
    }

    int cacheSize() {
        return cache.size();
    }

    public CimdClientMetadata fetch(String clientIdUrl) {
        CacheEntry cached = cache.get(clientIdUrl);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.metadata();
        }
        if (!urlValidator.isSafe(clientIdUrl)) {
            throw new CimdDocumentInvalidException("CIMD client_id URL failed the SSRF safety check: " + clientIdUrl);
        }

        try {
            return restClient.get()
                    .uri(clientIdUrl)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readDocument(clientIdUrl, response));
        } catch (RestClientException e) {
            throw new CimdDocumentUnavailableException("Failed to fetch CIMD document: " + clientIdUrl, e);
        }
    }

    private CimdClientMetadata readDocument(String url, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is3xxRedirection()) {
            throw new CimdDocumentInvalidException("CIMD document endpoint responded with a redirect: " + url);
        }
        if (status.value() == 429) {
            throw new CimdDocumentUnavailableException("CIMD document endpoint is rate limiting: " + url);
        }
        if (status.is4xxClientError()) {
            throw new CimdDocumentInvalidException("CIMD document endpoint responded with a client error: " + url);
        }
        if (status.is5xxServerError()) {
            throw new CimdDocumentUnavailableException("CIMD document endpoint responded with a server error: " + url);
        }

        HttpHeaders headers = response.getHeaders();
        MediaType contentType = headers.getContentType();
        if (contentType == null
                || !(MediaType.APPLICATION_JSON.isCompatibleWith(contentType) || contentType.getSubtype().endsWith("+json"))) {
            throw new CimdDocumentInvalidException("CIMD document has an unexpected Content-Type: " + url);
        }
        if (headers.getContentLength() > MAX_BODY_BYTES) {
            throw new CimdDocumentInvalidException("CIMD document exceeds the size limit: " + url);
        }

        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new CimdDocumentInvalidException("CIMD document exceeds the size limit: " + url);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new CimdDocumentInvalidException("CIMD document is not valid JSON: " + url, e);
        }
        if (root == null || !root.isObject()) {
            throw new CimdDocumentInvalidException("CIMD document body must be a JSON object: " + url);
        }

        JsonNode clientIdNode = root.get("client_id");
        if (clientIdNode == null || !clientIdNode.isTextual() || !url.equals(clientIdNode.asText())) {
            throw new CimdDocumentInvalidException("CIMD document client_id does not match the requested URL: " + url);
        }

        JsonNode authMethodNode = root.get("token_endpoint_auth_method");
        if (authMethodNode == null || !authMethodNode.isTextual() || !"none".equals(authMethodNode.asText())) {
            throw new CimdDocumentInvalidException("CIMD document token_endpoint_auth_method must be \"none\": " + url);
        }

        Set<String> redirectUris = readRedirectUris(root.get("redirect_uris"), url);

        JsonNode clientNameNode = root.get("client_name");
        String rawClientName = (clientNameNode != null && clientNameNode.isTextual()) ? clientNameNode.asText() : null;
        String clientName;
        try {
            clientName = policy.sanitizeClientName(rawClientName, URI.create(url).getHost());
        } catch (IllegalArgumentException e) {
            throw new CimdDocumentInvalidException("CIMD document client_name is invalid: " + url, e);
        }

        JsonNode clientUriNode = root.get("client_uri");
        String clientUri = (clientUriNode != null && clientUriNode.isTextual()) ? clientUriNode.asText() : null;

        CimdClientMetadata metadata = new CimdClientMetadata(url, clientName, clientUri, redirectUris);
        cacheIfAllowed(url, metadata, headers);
        return metadata;
    }

    private Set<String> readRedirectUris(JsonNode node, String url) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new CimdDocumentInvalidException("CIMD document redirect_uris must be a non-empty array: " + url);
        }
        Set<String> redirectUris = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (!element.isTextual() || !policy.isRegistrableRedirectUri(element.asText())) {
                throw new CimdDocumentInvalidException("CIMD document redirect_uris contains a non-registrable URI: " + url);
            }
            redirectUris.add(element.asText());
        }
        return redirectUris;
    }

    private void cacheIfAllowed(String url, CimdClientMetadata metadata, HttpHeaders headers) {
        // Cache-Control 지시어는 대소문자를 구분하지 않는다(RFC 9111) — 소문자로 맞춘 뒤 판정한다.
        String cacheControl = headers.getCacheControl() == null
                ? null
                : headers.getCacheControl().toLowerCase(Locale.ROOT);
        if (cacheControl != null && (cacheControl.contains("no-store") || cacheControl.contains("no-cache"))) {
            cache.remove(url);
            return;
        }
        Duration ttl = DEFAULT_MAX_AGE;
        if (cacheControl != null) {
            Matcher matcher = MAX_AGE_PATTERN.matcher(cacheControl);
            if (matcher.find()) {
                Duration requested = Duration.ofSeconds(Long.parseLong(matcher.group(1)));
                ttl = requested.compareTo(MAX_MAX_AGE) > 0 ? MAX_MAX_AGE : requested;
            }
        }
        // 로그인 없이도 임의의 https 주소로 문서 조회를 일으킬 수 있어, 유효한 문서를 내는 주소를
        // 계속 바꿔 대면 캐시가 끝없이 자란다 — 만료분을 치우고도 가득 차면 새 주소는 캐시하지 않는다.
        Instant now = clock.instant();
        cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        if (cache.size() >= maxCacheEntries && !cache.containsKey(url)) {
            return;
        }
        cache.put(url, new CacheEntry(metadata, now.plus(ttl)));
    }

    private record CacheEntry(CimdClientMetadata metadata, Instant expiresAt) {
    }
}
