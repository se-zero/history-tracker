package com.history.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalServiceAuthenticationFilter internalServiceAuthenticationFilter;

    // stateless API 보안 필터 체인 및 공개 인증 경로 설정
    @Bean
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
                                // Slack Events API — JWT 없이 열려야 한다. 서명 검증(SlackSignatureVerifier)이 유일한 인증 수단이다.
                                "/api/v1/slack/events",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(internalServiceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
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
