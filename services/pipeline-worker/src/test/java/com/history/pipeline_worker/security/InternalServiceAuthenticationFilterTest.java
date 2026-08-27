package com.history.pipeline_worker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("InternalServiceAuthenticationFilter: 내부 서비스 인증 필터")
class InternalServiceAuthenticationFilterTest {

    private static final String SHARED_SECRET = "shared-secret";

    private final InternalServiceAuthenticationFilter filter =
            new InternalServiceAuthenticationFilter(SHARED_SECRET);

    @Test
    @DisplayName("수집 트리거 경로에서 일치하는 토큰 허용")
    void acceptsMatchingTokenForCollectPath() throws Exception {
        MockHttpServletRequest request = requestTo("POST", "/api/v1/collect/github");
        request.addHeader(InternalServiceAuthenticationFilter.HEADER_NAME, SHARED_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("수집 트리거 경로에서 토큰 누락 시 401, 체인 미통과")
    void rejectsMissingTokenForCollectPath() throws Exception {
        MockHttpServletRequest request = requestTo("POST", "/api/v1/collect/github");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("수집 트리거 경로에서 토큰 불일치 시 401, 체인 미통과")
    void rejectsMismatchedTokenForCollectPath() throws Exception {
        MockHttpServletRequest request = requestTo("POST", "/api/v1/collect/github");
        request.addHeader(InternalServiceAuthenticationFilter.HEADER_NAME, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("raw 샘플 경로도 토큰 누락 시 401로 동일하게 보호된다")
    void rejectsMissingTokenForRawPath() throws Exception {
        MockHttpServletRequest request = requestTo("POST", "/api/v1/raw/github");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("GitHub webhook 경로는 토큰 없이도 필터를 통과한다 — HMAC 서명 검증이 대신 지킨다")
    void ignoresGitHubWebhookPath() throws Exception {
        MockHttpServletRequest request = requestTo("POST", "/api/v1/webhook/github");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("보호 대상이 아닌 경로는 토큰 없이도 필터를 통과한다")
    void ignoresNonProtectedPath() throws Exception {
        MockHttpServletRequest request = requestTo("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isTrue();
    }

    @Test
    @DisplayName("빈 토큰으로 생성하면 기동 시 즉시 실패한다")
    void rejectsBlankTokenAtConstruction() {
        assertThatThrownBy(() -> new InternalServiceAuthenticationFilter(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 토큰으로 생성하면 기동 시 즉시 실패한다")
    void rejectsNullTokenAtConstruction() {
        assertThatThrownBy(() -> new InternalServiceAuthenticationFilter(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletRequest requestTo(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static class TrackingFilterChain implements FilterChain {

        private boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }
}
