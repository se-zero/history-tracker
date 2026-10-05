package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.oauth.McpOAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("CimdDocumentFetcher: CIMD 문서 조회·검증·캐싱")
class CimdDocumentFetcherTest {

    private static final String CIMD_URL = "https://cimd.example/client";
    private static final String URL_A = "https://cimd.example/a";
    private static final String URL_B = "https://cimd.example/b";
    private static final String URL_C = "https://cimd.example/c";
    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("정상 문서 → 4개 필드 매핑, GET Accept: application/json")
    void fetchReturnsMetadataFromValidDocument() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON));

        CimdClientMetadata metadata = fixture.fetcher.fetch(CIMD_URL);

        assertThat(metadata.clientId()).isEqualTo(CIMD_URL);
        assertThat(metadata.clientName()).isEqualTo("Claude Code");
        assertThat(metadata.clientUri()).isEqualTo("https://claude.ai");
        assertThat(metadata.redirectUris()).containsExactlyInAnyOrder("http://localhost/callback", "http://127.0.0.1/callback");
        fixture.server.verify();
    }

    @Test
    @DisplayName("client_uri 없는 문서 → clientUri는 null")
    void fetchReturnsNullClientUriWhenDocumentOmitsIt() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.remove("client_uri");
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        CimdClientMetadata metadata = fixture.fetcher.fetch(CIMD_URL);

        assertThat(metadata.clientUri()).isNull();
    }

    @Test
    @DisplayName("3xx 리다이렉트 응답 → CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenRedirected() {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("https://cimd.example/other")));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("404 → CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenNotFound() {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("5xx 응답 → CimdDocumentUnavailableException")
    void fetchThrowsUnavailableWhenServerError() {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentUnavailableException.class);
    }

    @Test
    @DisplayName("연결·읽기 타임아웃(IOException → ResourceAccessException) → CimdDocumentUnavailableException")
    void fetchThrowsUnavailableOnConnectionTimeout() {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentUnavailableException.class);
    }

    @Test
    @DisplayName("Content-Type이 application/json이 아니면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenContentTypeIsNotJson() {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("바디가 JSON 객체가 아니라 배열이면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenBodyIsJsonArray() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(List.of("a", "b")), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("client_id 끝에 슬래시가 붙어 요청 URL과 불일치하면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenClientIdHasTrailingSlash() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("client_id", CIMD_URL + "/");
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("client_id 대소문자가 다르면 CimdDocumentInvalidException(정규화 비교 금지)")
    void fetchThrowsInvalidWhenClientIdCaseDiffers() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("client_id", CIMD_URL.toUpperCase(Locale.ROOT));
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("token_endpoint_auth_method 없으면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenTokenEndpointAuthMethodMissing() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.remove("token_endpoint_auth_method");
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("token_endpoint_auth_method가 none이 아니면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenTokenEndpointAuthMethodIsNotNone() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("token_endpoint_auth_method", "client_secret_post");
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("redirect_uris가 빈 배열이면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenRedirectUrisEmpty() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("redirect_uris", List.of());
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("redirect_uris 중 하나라도 등록 불가능한 URI면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenRedirectUriIsNotRegistrable() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("redirect_uris", List.of("http://localhost/callback", "http://evil.example/cb"));
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("client_name 없으면 URL 호스트로 대체")
    void fetchFallsBackToHostWhenClientNameMissing() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.remove("client_name");
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        CimdClientMetadata metadata = fixture.fetcher.fetch(CIMD_URL);

        assertThat(metadata.clientName()).isEqualTo("cimd.example");
    }

    @Test
    @DisplayName("client_name이 101자면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenClientNameTooLong() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("client_name", "a".repeat(101));
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("Content-Length 헤더가 65536을 초과하면 바디를 읽지 않고 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenContentLengthHeaderExceedsLimit() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON).header("Content-Length", "70000"));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("Content-Length 헤더 없이 바디가 65536바이트를 넘으면 CimdDocumentInvalidException")
    void fetchThrowsInvalidWhenBodyExceedsLimitWithoutContentLengthHeader() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        Map<String, Object> doc = baseDocument();
        doc.put("padding", "a".repeat(70_000));
        fixture.server.expect(once(), requestTo(CIMD_URL)).andRespond(withSuccess(json(doc), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
    }

    @Test
    @DisplayName("Cache-Control: max-age=600 → 그 안(599초)에서는 재요청 없이 캐시로 응답")
    void cachesResponseWithinMaxAgeWindow() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON).header("Cache-Control", "max-age=600"));

        fixture.fetcher.fetch(CIMD_URL);
        fixture.clock.advance(Duration.ofSeconds(599));
        fixture.fetcher.fetch(CIMD_URL);

        fixture.server.verify();
    }

    @Test
    @DisplayName("Cache-Control 지시어는 대소문자 무관 — Max-Age=600도 그 안에서는 재요청 없음")
    void cachesResponseWhenMaxAgeDirectiveUsesDifferentCase() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(CIMD_URL))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON).header("Cache-Control", "Public, Max-Age=600"));

        fixture.fetcher.fetch(CIMD_URL);
        // 기본 TTL(300초)보다 뒤, 선언된 600초보다 앞 — 대소문자를 무시해야만 캐시가 살아 있다
        fixture.clock.advance(Duration.ofSeconds(450));
        fixture.fetcher.fetch(CIMD_URL);

        fixture.server.verify();
    }

    @Test
    @DisplayName("Cache-Control: max-age=600 → 만료(601초) 뒤에는 재요청")
    void refetchesAfterMaxAgeExpires() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(times(2), requestTo(CIMD_URL))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON).header("Cache-Control", "max-age=600"));

        fixture.fetcher.fetch(CIMD_URL);
        fixture.clock.advance(Duration.ofSeconds(601));
        fixture.fetcher.fetch(CIMD_URL);

        fixture.server.verify();
    }

    @Test
    @DisplayName("Cache-Control: no-store → 매번 재요청")
    void doesNotCacheWhenNoStoreDirectivePresent() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(times(2), requestTo(CIMD_URL))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON).header("Cache-Control", "no-store"));

        fixture.fetcher.fetch(CIMD_URL);
        fixture.fetcher.fetch(CIMD_URL);

        fixture.server.verify();
    }

    @Test
    @DisplayName("Cache-Control 헤더 없음 → 기본 300초 캐시(299초는 재요청 없음, 301초에는 재요청)")
    void appliesDefaultTtlWhenNoCacheControlHeaderPresent() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(times(2), requestTo(CIMD_URL))
                .andRespond(withSuccess(json(baseDocument()), MediaType.APPLICATION_JSON));

        fixture.fetcher.fetch(CIMD_URL);
        fixture.clock.advance(Duration.ofSeconds(299));
        fixture.fetcher.fetch(CIMD_URL);
        fixture.clock.advance(Duration.ofSeconds(2));
        fixture.fetcher.fetch(CIMD_URL);

        fixture.server.verify();
    }

    @Test
    @DisplayName("SSRF 가드 불합격 URL → HTTP 요청 없이 CimdDocumentInvalidException")
    void fetchThrowsInvalidWithoutHttpRequestWhenUrlFailsSafetyCheck() {
        CimdDocumentFetcherFixture fixture = fixture(privateUrlValidator());

        assertThatThrownBy(() -> fixture.fetcher.fetch(CIMD_URL)).isInstanceOf(CimdDocumentInvalidException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("다른 주소를 캐시할 때 만료된 항목은 지워진다")
    void purgesExpiredEntriesWhenCachingAnotherUrl() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(URL_A)).andRespond(withSuccess(json(documentFor(URL_A)), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(URL_B)).andRespond(withSuccess(json(documentFor(URL_B)), MediaType.APPLICATION_JSON));

        fixture.fetcher.fetch(URL_A);
        fixture.clock.advance(Duration.ofSeconds(301));
        fixture.fetcher.fetch(URL_B);

        assertThat(fixture.fetcher.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료되지 않은 항목은 다른 주소를 캐시해도 지워지지 않는다")
    void keepsUnexpiredEntriesWhenCachingAnotherUrl() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(URL_A)).andRespond(withSuccess(json(documentFor(URL_A)), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(URL_B)).andRespond(withSuccess(json(documentFor(URL_B)), MediaType.APPLICATION_JSON));

        fixture.fetcher.fetch(URL_A);
        fixture.clock.advance(Duration.ofMinutes(1));
        fixture.fetcher.fetch(URL_B);
        fixture.fetcher.fetch(URL_A);

        assertThat(fixture.fetcher.cacheSize()).isEqualTo(2);
        fixture.server.verify();
    }

    @Test
    @DisplayName("캐시가 상한에 닿으면 새 항목은 캐시하지 않는다(결과는 정상 반환, 기존 항목은 계속 캐시 응답)")
    void doesNotCacheNewEntryWhenCacheIsFull() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture(2);
        fixture.server.expect(once(), requestTo(URL_A)).andRespond(withSuccess(json(documentFor(URL_A)), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(URL_B)).andRespond(withSuccess(json(documentFor(URL_B)), MediaType.APPLICATION_JSON));
        fixture.server.expect(times(2), requestTo(URL_C)).andRespond(withSuccess(json(documentFor(URL_C)), MediaType.APPLICATION_JSON));

        fixture.fetcher.fetch(URL_A);
        fixture.fetcher.fetch(URL_B);
        CimdClientMetadata first = fixture.fetcher.fetch(URL_C);
        CimdClientMetadata second = fixture.fetcher.fetch(URL_C);
        fixture.fetcher.fetch(URL_A);
        fixture.fetcher.fetch(URL_B);

        assertThat(first.clientId()).isEqualTo(URL_C);
        assertThat(second.clientId()).isEqualTo(URL_C);
        assertThat(fixture.fetcher.cacheSize()).isEqualTo(2);
        fixture.server.verify();
    }

    @Test
    @DisplayName("상한에 닿아 있어도 만료된 항목이 빠지면 새 항목이 캐시된다")
    void cachesNewEntryWhenExpiredEntriesFreeCapacity() throws Exception {
        CimdDocumentFetcherFixture fixture = fixture(2);
        fixture.server.expect(once(), requestTo(URL_A)).andRespond(withSuccess(json(documentFor(URL_A)), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(URL_B)).andRespond(withSuccess(json(documentFor(URL_B)), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(URL_C)).andRespond(withSuccess(json(documentFor(URL_C)), MediaType.APPLICATION_JSON));

        fixture.fetcher.fetch(URL_A);
        fixture.fetcher.fetch(URL_B);
        fixture.clock.advance(Duration.ofSeconds(301));
        fixture.fetcher.fetch(URL_C);
        fixture.fetcher.fetch(URL_C);

        assertThat(fixture.fetcher.cacheSize()).isEqualTo(1);
        fixture.server.verify();
    }

    // ── 헬퍼 ──

    private CimdDocumentFetcherFixture fixture() {
        return fixture(publicUrlValidator());
    }

    private CimdDocumentFetcherFixture fixture(int maxCacheEntries) {
        return fixture(publicUrlValidator(), maxCacheEntries);
    }

    private CimdDocumentFetcherFixture fixture(SafeUrlValidator urlValidator) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock(BASE_INSTANT);
        CimdDocumentFetcher fetcher = new CimdDocumentFetcher(builder.build(), urlValidator, policy(), clock);
        return new CimdDocumentFetcherFixture(fetcher, server, clock);
    }

    private CimdDocumentFetcherFixture fixture(SafeUrlValidator urlValidator, int maxCacheEntries) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock(BASE_INSTANT);
        CimdDocumentFetcher fetcher = new CimdDocumentFetcher(builder.build(), urlValidator, policy(), clock, maxCacheEntries);
        return new CimdDocumentFetcherFixture(fetcher, server, clock);
    }

    private Map<String, Object> documentFor(String url) {
        Map<String, Object> doc = baseDocument();
        doc.put("client_id", url);
        return doc;
    }

    private McpRegisteredClientPolicy policy() {
        return new McpRegisteredClientPolicy(new McpOAuthProperties("http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30), "/oauth/consent"));
    }

    private SafeUrlValidator publicUrlValidator() {
        return resolvingValidator("93.184.216.34");
    }

    private SafeUrlValidator privateUrlValidator() {
        return resolvingValidator("10.0.0.1");
    }

    private SafeUrlValidator resolvingValidator(String literalIp) {
        return new SafeUrlValidator(host -> {
            try {
                return new InetAddress[] { InetAddress.getByName(literalIp) };
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private Map<String, Object> baseDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("client_id", CIMD_URL);
        doc.put("client_name", "Claude Code");
        doc.put("client_uri", "https://claude.ai");
        doc.put("redirect_uris", List.of("http://localhost/callback", "http://127.0.0.1/callback"));
        doc.put("grant_types", List.of("authorization_code", "refresh_token"));
        doc.put("response_types", List.of("code"));
        doc.put("token_endpoint_auth_method", "none");
        return doc;
    }

    private String json(Map<String, Object> doc) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(doc);
    }

    private record CimdDocumentFetcherFixture(CimdDocumentFetcher fetcher, MockRestServiceServer server, MutableClock clock) {
    }

    // 실제 흐름을 그대로 타되, 시각만 테스트가 직접 이동시키는 Clock
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
