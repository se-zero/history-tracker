package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.oauth.OAuthRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@DisplayName("OAuthRateLimitFilter: 요청자 IP별 상한 초과 시 429")
class OAuthRateLimitFilterTest {

    private static final String HEADER = "CF-Connecting-IP";
    private static final String AUTHORIZE = "/oauth2/authorize";
    private static final int LIMIT = 2;

    @Test
    @DisplayName("헤더 값이 있으면 헤더 값이 키 — 연결 주소가 같아도 헤더가 다르면 별개 예산")
    void headerValueIsKeyEvenWhenRemoteAddressIsSame() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);

        exhaust(filter, "10.0.0.1", "203.0.113.1");

        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(429);
        assertThat(status(filter, "10.0.0.1", "203.0.113.2")).isEqualTo(200);
    }

    @Test
    @DisplayName("헤더 값이 있으면 연결 주소가 달라도 헤더가 같으면 같은 예산")
    void sameHeaderSharesBudgetAcrossRemoteAddresses() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.2", "203.0.113.1")).isEqualTo(200);

        assertThat(status(filter, "10.0.0.3", "203.0.113.1")).isEqualTo(429);
    }

    @Test
    @DisplayName("헤더 값 앞뒤 공백은 제거하고 키로 쓴다")
    void trimsHeaderValue() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        assertThat(status(filter, "10.0.0.1", " 203.0.113.1 ")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(200);

        assertThat(status(filter, "10.0.0.1", "203.0.113.1  ")).isEqualTo(429);
    }

    @Test
    @DisplayName("헤더가 없으면 연결 주소가 키")
    void remoteAddressIsKeyWhenHeaderIsAbsent() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);

        exhaust(filter, "10.0.0.1", null);

        assertThat(status(filter, "10.0.0.1", null)).isEqualTo(429);
        assertThat(status(filter, "10.0.0.2", null)).isEqualTo(200);
    }

    @Test
    @DisplayName("헤더 값이 공백뿐이면 연결 주소가 키")
    void remoteAddressIsKeyWhenHeaderIsBlank() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);

        exhaust(filter, "10.0.0.1", "   ");

        assertThat(status(filter, "10.0.0.1", "   ")).isEqualTo(429);
        assertThat(status(filter, "10.0.0.2", "   ")).isEqualTo(200);
    }

    @Test
    @DisplayName("clientIpHeader가 null이면 헤더가 와도 무시하고 연결 주소를 키로 쓴다")
    void ignoresHeaderWhenClientIpHeaderIsNotConfigured() throws Exception {
        OAuthRateLimitFilter filter = filter(null);
        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.1", "203.0.113.2")).isEqualTo(200);

        assertThat(status(filter, "10.0.0.1", "203.0.113.3")).isEqualTo(429);
        assertThat(status(filter, "10.0.0.2", "203.0.113.1")).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 /64의 IPv6 두 주소는 예산을 공유")
    void ipv6AddressesInSameSlash64ShareBudget() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        assertThat(status(filter, "10.0.0.1", "2001:db8:1:2::1")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.1", "2001:db8:1:2:ffff::9")).isEqualTo(200);

        assertThat(status(filter, "10.0.0.1", "2001:db8:1:2:1:2:3:4")).isEqualTo(429);
    }

    @Test
    @DisplayName("다른 /64의 IPv6 주소는 별개 예산")
    void ipv6AddressesInDifferentSlash64HaveSeparateBudgets() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);

        exhaust(filter, "10.0.0.1", "2001:db8:1:2::1");

        assertThat(status(filter, "10.0.0.1", "2001:db8:1:2::1")).isEqualTo(429);
        assertThat(status(filter, "10.0.0.1", "2001:db8:1:3::1")).isEqualTo(200);
    }

    @Test
    @DisplayName("압축 표기(::)가 달라도 같은 /64면 예산을 공유")
    void ipv6CompressionDoesNotSplitSlash64() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        assertThat(status(filter, "10.0.0.1", "2001:db8::1")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.1", "2001:0db8:0:0:0:0:0:5")).isEqualTo(200);

        assertThat(status(filter, "10.0.0.1", "2001:db8:0:0:abcd::7")).isEqualTo(429);
    }

    @Test
    @DisplayName("헤더가 없을 때 연결 주소가 IPv6여도 /64 단위로 센다")
    void ipv6RemoteAddressIsGroupedBySlash64() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        assertThat(status(filter, "2001:db8:1:2::1", null)).isEqualTo(200);
        assertThat(status(filter, "2001:db8:1:2::2", null)).isEqualTo(200);

        assertThat(status(filter, "2001:db8:1:2::3", null)).isEqualTo(429);
    }

    @Test
    @DisplayName("IPv4 두 주소는 같은 대역이어도 별개 예산")
    void ipv4AddressesHaveSeparateBudgets() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);

        exhaust(filter, "10.0.0.1", "203.0.113.1");

        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(429);
        assertThat(status(filter, "10.0.0.1", "203.0.113.2")).isEqualTo(200);
    }

    @Test
    @DisplayName("IP 형식이 아닌 값도 예외 없이 그 문자열 자체가 키")
    void nonIpValueIsUsedAsKeyWithoutError() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);

        exhaust(filter, "10.0.0.1", "not-an-ip");

        assertThat(status(filter, "10.0.0.2", "not-an-ip")).isEqualTo(429);
        assertThat(status(filter, "10.0.0.1", "also-not-an-ip")).isEqualTo(200);
    }

    @Test
    @DisplayName("상한 초과 시 429 + Retry-After(양의 정수) + JSON 본문, 체인은 호출하지 않는다")
    void rejectsWithTooManyRequestsAndDoesNotInvokeChain() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        exhaust(filter, "10.0.0.1", "203.0.113.1");
        MockHttpServletRequest request = request(AUTHORIZE, "10.0.0.1", "203.0.113.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(response.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("error").asText())
                .isEqualTo("too_many_requests");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("429 본문에 허용 화면이 그대로 보여 줄 한국어 message를 UTF-8로 싣는다")
    void rejectionBodyCarriesKoreanMessageForConsentScreen() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        exhaust(filter, "10.0.0.1", "203.0.113.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(AUTHORIZE, "10.0.0.1", "203.0.113.1"), response, new MockFilterChain());

        // 허용 화면(OAuthConsentCard)은 응답의 message를 그대로 보여 준다 — 없으면 "요청이 올바르지 않습니다"로 잘못 안내한다.
        // 인코딩을 명시하지 않으면 실제 서블릿 컨테이너는 ISO-8859-1로 써서 한글이 '?'로 깨진다.
        // Mock 응답은 JSON이면 알아서 UTF-8로 풀어 주므로, 명시했을 때만 붙는 Content-Type의 charset으로 확인한다.
        String retryAfter = response.getHeader("Retry-After");
        String body = new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response.getContentType()).containsIgnoringCase("charset=UTF-8");
        assertThat(new ObjectMapper().readTree(body).get("message").asText())
                .isEqualTo("요청이 너무 많습니다. " + retryAfter + "초 뒤에 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("허용되면 체인을 진행하고 응답은 건드리지 않는다")
    void allowedRequestContinuesChain() throws Exception {
        OAuthRateLimitFilter filter = filter(HEADER);
        MockHttpServletRequest request = request(AUTHORIZE, "10.0.0.1", "203.0.113.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    @DisplayName("매처가 안 맞는 요청은 상한을 넘겨도 통과하고 예산을 쓰지 않는다")
    void unmatchedRequestPassesThroughWithoutConsumingBudget() throws Exception {
        RequestMatcher oauthOnly = request -> request.getRequestURI().startsWith("/oauth2/");
        OAuthRateLimitFilter filter = filter(HEADER, oauthOnly);

        for (int i = 0; i < LIMIT + 3; i++) {
            MockHttpServletRequest other = request("/api/v1/projects", "10.0.0.1", "203.0.113.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(other, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }

        // 매처 밖 호출이 예산을 썼다면 여기서 이미 막힌다
        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(200);
        assertThat(status(filter, "10.0.0.1", "203.0.113.1")).isEqualTo(429);
    }

    private OAuthRateLimitFilter filter(String clientIpHeader) {
        return filter(clientIpHeader, AnyRequestMatcher.INSTANCE);
    }

    private OAuthRateLimitFilter filter(String clientIpHeader, RequestMatcher matcher) {
        OAuthRateLimitProperties properties = new OAuthRateLimitProperties(LIMIT, clientIpHeader);
        return new OAuthRateLimitFilter(new OAuthRateLimiter(properties), properties, matcher);
    }

    private void exhaust(OAuthRateLimitFilter filter, String remoteAddr, String headerValue) throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(status(filter, remoteAddr, headerValue)).isEqualTo(200);
        }
    }

    private int status(OAuthRateLimitFilter filter, String remoteAddr, String headerValue) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(AUTHORIZE, remoteAddr, headerValue), response, new MockFilterChain());
        return response.getStatus();
    }

    private MockHttpServletRequest request(String uri, String remoteAddr, String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(remoteAddr);
        if (headerValue != null) {
            request.addHeader(HEADER, headerValue);
        }
        return request;
    }
}
