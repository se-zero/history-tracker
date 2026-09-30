package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.history.backend.oauth.service.ConsentTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentTicketAuthenticationFilter: 티켓 쿠키로 authorize 요청 인증")
class ConsentTicketAuthenticationFilterTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final String RAW_QUERY = "response_type=code&client_id=abc&state=s1";
    private static final String TICKET = "ticket-value";

    @Mock
    private ConsentTicketService consentTicketService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("티켓 소비 성공 시 String principal로 인증하고 체인을 계속 진행한다")
    void authenticatesUserWhenTicketIsConsumed() throws Exception {
        when(consentTicketService.consume(TICKET, RAW_QUERY)).thenReturn(Optional.of(USER_ID));
        MockHttpServletRequest request = authorizeRequest("GET", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked.get()).isTrue();
        Authentication authentication = chain.authentication.get();
        assertThat(authentication).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isInstanceOf(String.class).isEqualTo(USER_ID.toString());
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("티켓 소비 성공 시 쿠키를 지운다(Max-Age=0, 같은 Path)")
    void clearsTicketCookieAfterSuccessfulConsume() throws Exception {
        when(consentTicketService.consume(TICKET, RAW_QUERY)).thenReturn(Optional.of(USER_ID));
        MockHttpServletRequest request = authorizeRequest("GET", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new CapturingChain());

        assertClearCookieHeader(response, false);
    }

    @Test
    @DisplayName("티켓 소비 실패 시에도 쿠키는 지우고, 인증은 세팅하지 않은 채 체인을 계속 진행한다")
    void clearsTicketCookieButDoesNotAuthenticateWhenConsumeFails() throws Exception {
        when(consentTicketService.consume(TICKET, RAW_QUERY)).thenReturn(Optional.empty());
        MockHttpServletRequest request = authorizeRequest("GET", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked.get()).isTrue();
        assertThat(chain.authentication.get()).isNull();
        assertClearCookieHeader(response, false);
    }

    @Test
    @DisplayName("HTTPS 요청이면 삭제 쿠키에도 Secure가 붙는다")
    void clearCookieIsSecureForHttpsRequest() throws Exception {
        when(consentTicketService.consume(TICKET, RAW_QUERY)).thenReturn(Optional.empty());
        MockHttpServletRequest request = authorizeRequest("GET", true);
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new CapturingChain());

        assertClearCookieHeader(response, true);
    }

    @Test
    @DisplayName("쿠키가 없으면 consume 호출·Set-Cookie·인증 없이 체인만 진행")
    void passesThroughWhenTicketCookieIsAbsent() throws Exception {
        MockHttpServletRequest request = authorizeRequest("GET", false);
        request.setCookies(new Cookie("other", "value"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked.get()).isTrue();
        assertThat(chain.authentication.get()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        verifyNoInteractions(consentTicketService);
    }

    @Test
    @DisplayName("POST 요청은 쿠키가 있어도 무시한다(consume 없음, 쿠키 유지, 인증 없음)")
    void ignoresPostRequest() throws Exception {
        MockHttpServletRequest request = authorizeRequest("POST", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked.get()).isTrue();
        assertThat(chain.authentication.get()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        verifyNoInteractions(consentTicketService);
    }

    @Test
    @DisplayName("authorize가 아닌 경로는 쿠키가 있어도 무시한다")
    void ignoresOtherPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/token");
        request.setQueryString(RAW_QUERY);
        request.setCookies(new Cookie(ConsentTicketCookies.NAME, TICKET));
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked.get()).isTrue();
        assertThat(chain.authentication.get()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        verifyNoInteractions(consentTicketService);
    }

    @Test
    @DisplayName("쿼리 원문(getQueryString)을 그대로 consume에 넘긴다")
    void passesRawQueryStringToConsume() throws Exception {
        String encodedQuery = "redirect_uri=http%3A%2F%2Flocalhost%3A53421%2Fcallback&state=a+b";
        when(consentTicketService.consume(TICKET, encodedQuery)).thenReturn(Optional.empty());
        MockHttpServletRequest request = authorizeRequest("GET", true);
        request.setQueryString(encodedQuery);

        filter().doFilter(request, new MockHttpServletResponse(), new CapturingChain());

        verify(consentTicketService).consume(TICKET, encodedQuery);
    }

    private ConsentTicketAuthenticationFilter filter() {
        return new ConsentTicketAuthenticationFilter(consentTicketService);
    }

    private MockHttpServletRequest authorizeRequest(String method, boolean withTicketCookie) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/oauth2/authorize");
        request.setQueryString(RAW_QUERY);
        if (withTicketCookie) {
            request.setCookies(new Cookie(ConsentTicketCookies.NAME, TICKET));
        }
        return request;
    }

    private void assertClearCookieHeader(MockHttpServletResponse response, boolean secure) {
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith(ConsentTicketCookies.NAME + "=;");
        assertThat(setCookie).contains("Path=/oauth2/authorize");
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie.contains("Secure")).isEqualTo(secure);
    }

    // 체인이 실행되는 시점의 SecurityContext를 읽어 둔다 — 필터가 세팅한 인증은 다음 필터가 볼 수 있어야 한다.
    private static class CapturingChain implements FilterChain {

        private final AtomicBoolean invoked = new AtomicBoolean();
        private final AtomicReference<Authentication> authentication = new AtomicReference<>();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            invoked.set(true);
            authentication.set(SecurityContextHolder.getContext().getAuthentication());
        }
    }
}
