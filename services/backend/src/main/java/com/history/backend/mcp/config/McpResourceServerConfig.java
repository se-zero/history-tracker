package com.history.backend.mcp.config;

import java.util.List;

import com.history.backend.oauth.McpOAuthProperties;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

// MCP 리소스 서버(/mcp) — RS256 access token만 인정하는 문지기
@Configuration
public class McpResourceServerConfig {

    private static final String MCP_SCOPE = "mcp:query";

    // 이 앱의 JwtDecoder 빈은 이것 하나다 — SPA는 자체 HS256 토큰을 쓰고, Boot 자동구성이 만드는
    // JwtDecoder는 이 빈으로 대체된다. aud 검사가 없으면 같은 서명키로 발급된 다른 용도 토큰까지 통과한다.
    @Bean
    JwtDecoder mcpJwtDecoder(JWKSource<SecurityContext> jwkSource, McpOAuthProperties mcpOAuthProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(mcpOAuthProperties.issuer()),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(mcpOAuthProperties.resourceUrl()))));
        return decoder;
    }

    @Bean
    @Order(2)
    SecurityFilterChain mcpResourceServerChain(
            HttpSecurity http, JwtDecoder mcpJwtDecoder, McpOAuthProperties mcpOAuthProperties) throws Exception {
        http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**").permitAll()
                        .anyRequest().hasAuthority("SCOPE_" + MCP_SCOPE))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(mcpJwtDecoder))
                        .authenticationEntryPoint(mcpBearerEntryPoint(mcpOAuthProperties))
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(builder -> builder
                                // 기본은 요청 URL로 계산되는데, 프록시 뒤에서 흔들리지 않게 issuer 기준으로 고정한다.
                                .resource(mcpOAuthProperties.resourceUrl())
                                .authorizationServers(servers -> {
                                    servers.clear();
                                    servers.add(mcpOAuthProperties.issuer());
                                })
                                .scopes(scopes -> {
                                    scopes.clear();
                                    scopes.add(MCP_SCOPE);
                                })
                                .bearerMethods(methods -> {
                                    methods.clear();
                                    methods.add("header");
                                }))));
        return http.build();
    }

    // Spring 기본 BearerTokenAuthenticationEntryPoint는 resource_metadata를 요청 URL 기준 루트 경로로
    // 자동 계산하고 설정 API가 없으며 scope는 토큰 오류 때만 붙인다 — MCP 규격(RFC 9728)은 고정 경로와
    // scope를 항상 요구해서 직접 조립한다. 403(scope 부족)은 Spring 기본 핸들러가 insufficient_scope를
    // 내므로 건드리지 않는다.
    private AuthenticationEntryPoint mcpBearerEntryPoint(McpOAuthProperties mcpOAuthProperties) {
        return (request, response, authException) -> {
            StringBuilder challenge = new StringBuilder("Bearer ");
            if (authException instanceof OAuth2AuthenticationException oauth2Exception) {
                String errorCode = oauth2Exception.getError().getErrorCode();
                if (errorCode != null) {
                    challenge.append("error=\"").append(errorCode).append("\", ");
                }
                String description = oauth2Exception.getError().getDescription();
                if (description != null) {
                    challenge.append("error_description=\"").append(description.replace("\"", "")).append("\", ");
                }
            }
            challenge.append("resource_metadata=\"")
                    .append(mcpOAuthProperties.protectedResourceMetadataUrl())
                    .append("\", scope=\"")
                    .append(MCP_SCOPE)
                    .append("\"");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge.toString());
        };
    }
}
