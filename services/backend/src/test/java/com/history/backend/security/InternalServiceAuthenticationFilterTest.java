package com.history.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("InternalServiceAuthenticationFilter: 내부 서비스 인증 필터")
class InternalServiceAuthenticationFilterTest {

    private final InternalServiceAuthenticationFilter filter =
            new InternalServiceAuthenticationFilter("shared-secret");

    @Test
    @DisplayName("내부 경로에서 일치하는 토큰 허용")
    void acceptsMatchingTokenForInternalPath() throws Exception {
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalServiceAuthenticationFilter.HEADER_NAME, "shared-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("내부 경로에서 잘못된 토큰 거부 → 401")
    void rejectsMissingOrInvalidTokenForInternalPath() throws Exception {
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalServiceAuthenticationFilter.HEADER_NAME, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("percent-encoding으로 우회한 내부 경로도 토큰 없이는 401")
    void rejectsPercentEncodedInternalPath() throws Exception {
        // getRequestURI()는 디코딩 전 원문이라 %69(='i')로 매칭을 우회할 수 있었다.
        // Spring MVC는 디코딩된 경로로 라우팅하므로 필터가 놓치면 컨트롤러에는 그대로 도달한다.
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/%69nternal/github/installations/456/token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("내부 경로가 아닌 요청은 필터 통과")
    void ignoresNonInternalPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain filterChain = new TrackingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.invoked).isTrue();
    }

    private MockHttpServletRequest internalRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/internal/github/installations/456/token");
    }

    private static class TrackingFilterChain implements FilterChain {

        private boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }
}
