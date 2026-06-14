package com.history.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalServiceAuthenticationFilterTest {

    private final InternalServiceAuthenticationFilter filter =
            new InternalServiceAuthenticationFilter("shared-secret");

    @Test
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
