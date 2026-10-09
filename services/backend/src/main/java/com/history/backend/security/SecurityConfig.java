package com.history.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.ErrorResponse;
import com.history.backend.oauth.OAuthRateLimitProperties;
import com.history.backend.oauth.security.OAuthRateLimitFilter;
import com.history.backend.oauth.security.OAuthRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalServiceAuthenticationFilter internalServiceAuthenticationFilter;
    private final OAuthRateLimiter oAuthRateLimiter;
    private final OAuthRateLimitProperties oAuthRateLimitProperties;

    // stateless API 보안 필터 체인 및 공개 인증 경로 설정.
    // securityMatcher가 없는 catch-all이라 인가 서버(1)·MCP 리소스 서버(2) 체인 뒤에 와야 한다.
    @Bean
    @Order(3)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/internal/**",
                                "/api/v1/auth/github/**",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/integrations/*/callback",
                                // Slack Events API·slash command — JWT 없이 열려야 한다. 서명 검증(SlackSignatureVerifier)이 유일한 인증 수단이다.
                                "/api/v1/slack/events",
                                "/api/v1/slack/commands",
                                // Paddle 결제 웹훅 — JWT 없이 열려야 한다. 서명 검증(PaddleSignatureVerifier)이 유일한 인증 수단이다.
                                "/api/v1/billing/webhook/paddle",
                                // 로그인 전 요금 페이지가 결제 버튼 노출 여부만 본다. 비밀값은 없다.
                                "/api/v1/billing/availability",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // 동의 화면 API도 인가 서버 주소와 같은 IP 예산을 쓴다. 인증 없는 요청도 세려고 인증 필터들보다 앞에 둔다.
                .addFilterBefore(new OAuthRateLimitFilter(oAuthRateLimiter, oAuthRateLimitProperties,
                        PathPatternRequestMatcher.withDefaults().matcher("/api/v1/oauth/consent/**")), SecurityContextHolderFilter.class)
                .addFilterBefore(internalServiceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    // JwtAuthenticationFilter는 @Component라 Boot가 서블릿 컨테이너에도 "/*"로 자동 등록하는데,
    // 그 인스턴스는 보안 체인(-100) 뒤에 한 번 더 돌아 MCP 체인이 통과시킨 RS256 Bearer를 HS256으로
    // 재검사해 401을 낸다. 보안 체인에는 addFilterBefore로 이미 넣고 있으므로 서블릿 등록만 끈다
    // — /api/** 동작은 변하지 않는다.
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // 미인증 요청에 대한 공통 JSON 에러 응답 처리
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                    "Authentication is required."
            ));
        };
    }
}
