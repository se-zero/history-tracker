package com.history.backend.oauth.security;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import com.history.backend.oauth.OAuthRateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

// 인가 서버 주소의 IP별 상한. 클라이언트 조회·티켓 소비보다 앞에 둬 거절된 요청이 외부 문서 조회나 행 생성을 일으키지 않게 한다.
// 빈으로 만들지 않는다 — @Component를 붙이면 서블릿 컨테이너에도 자동 등록돼 이중으로 돈다(ConsentTicketAuthenticationFilter와 같은 이유).
public class OAuthRateLimitFilter extends OncePerRequestFilter {

    private static final long MIN_RETRY_AFTER_SECONDS = 1;

    private final OAuthRateLimiter limiter;
    private final OAuthRateLimitProperties properties;
    private final RequestMatcher matcher;

    public OAuthRateLimitFilter(OAuthRateLimiter limiter, OAuthRateLimitProperties properties, RequestMatcher matcher) {
        this.limiter = limiter;
        this.properties = properties;
        this.matcher = matcher;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!matcher.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        Duration wait = limiter.acquire(clientKey(request));
        if (wait.isZero()) {
            filterChain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(MIN_RETRY_AFTER_SECONDS, (wait.toMillis() + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // message는 허용 화면(SPA)이 그대로 보여 주는 문구다 — 없으면 "요청이 올바르지 않습니다"로 잘못 안내된다.
        response.getWriter().write("{\"error\":\"too_many_requests\",\"error_description\":"
                + "\"Too many requests. Retry after " + retryAfter + " seconds.\","
                + "\"message\":\"요청이 너무 많습니다. " + retryAfter + "초 뒤에 다시 시도해 주세요.\"}");
    }

    private String clientKey(HttpServletRequest request) {
        String headerName = properties.clientIpHeader();
        String value = null;
        if (headerName != null && !headerName.isBlank()) {
            String header = request.getHeader(headerName);
            if (header != null && !header.isBlank()) {
                value = header.trim();
            }
        }
        return normalize(value != null ? value : request.getRemoteAddr());
    }

    // IPv6는 회선 하나가 /64 안의 주소를 사실상 무한히 가져 주소 단위로 세면 주소만 바꿔 상한을 피한다 — 앞 64비트로 묶는다.
    // 요청마다 도는 필터라 DNS 조회가 일어나면 안 된다. 호스트 이름이 올 수 있는 getByName(원문) 대신 ':'가 있을 때만
    // 대괄호 형식으로 파싱한다 — 대괄호 형식은 IPv6 리터럴이 아니면 조회 없이 UnknownHostException을 던진다.
    private static String normalize(String value) {
        if (value == null || value.indexOf(':') < 0) {
            return value;
        }
        try {
            InetAddress address = InetAddress.getByName("[" + value + "]");
            if (address instanceof Inet6Address) {
                return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
            }
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            return value;
        }
    }
}
