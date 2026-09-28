package com.history.backend.oauth.config;

import java.util.ArrayList;
import java.util.List;

import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.oauth.security.DefaultScopeAuthorizationRequestConverter;
import com.history.backend.oauth.security.McpAuthorizationRequestValidator;
import com.history.backend.oauth.security.PublicClientRefreshTokenAuthenticationConverter;
import com.history.backend.oauth.security.PublicClientRefreshTokenAuthenticationProvider;
import com.history.backend.oauth.security.PublicClientRefreshTokenGenerator;
import com.history.backend.oauth.service.OAuthJwkProvider;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

// MCP 클라이언트용 OAuth 2.1 인가 서버(/oauth2/**, /.well-known/oauth-authorization-server) 설정
@Configuration
@RequiredArgsConstructor
public class OAuthAuthorizationServerConfig {

    private final McpOAuthProperties mcpOAuthProperties;

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer(mcpOAuthProperties.issuer()).build();
    }

    // Boot 자동구성이 만드는 임시 키를 대체한다 — 다중 인스턴스·재기동에도 같은 키를 써야 한다.
    @Bean
    JWKSource<SecurityContext> jwkSource(OAuthJwkProvider oAuthJwkProvider) {
        return oAuthJwkProvider.jwkSource();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    // Spring 기본은 aud=client_id인데 MCP 규격은 리소스 URL을 요구한다.
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // List.of() 단일 원소를 그대로 넣으면 JDK 내부 타입이라 JdbcOAuth2AuthorizationService의
                // Jackson PolymorphicTypeValidator가 역직렬화를 거부한다 — ArrayList로 감싸서 넣는다.
                context.getClaims().audience(new ArrayList<>(List.of(mcpOAuthProperties.resourceUrl())));
            }
        };
    }

    // Spring 기본 조립(OAuth2ConfigurerUtils.getTokenGenerator)은 이 타입의 빈이 없을 때만 동작하며, 그 기본
    // refresh 생성기는 공개 클라이언트를 제외한다 — 그래서 직접 조립해 대체한다. 직접 조립하면 tokenCustomizer
    // 빈이 자동으로 붙지 않으므로 JwtGenerator에 직접 연결해야 access 토큰의 aud 클레임이 유지된다.
    @Bean
    OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator(
            JWKSource<SecurityContext> jwkSource, OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(tokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator, new OAuth2AccessTokenGenerator(), new PublicClientRefreshTokenGenerator());
    }

    @Bean
    @Order(1)
    SecurityFilterChain oauthAuthorizationServerChain(
            HttpSecurity http, RegisteredClientRepository registeredClientRepository) throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, server -> server
                        .authorizationServerMetadataEndpoint(metadata -> metadata.authorizationServerMetadataCustomizer(builder -> builder
                                // CIMD(Client ID Metadata Document) 지원 신호 — Spring에 전용 API가 없어 커스텀 클레임으로 알린다.
                                .claim("client_id_metadata_document_supported", true)
                                .scope("mcp:query")
                                // 공개 클라이언트(PKCE, client_secret 없음)만 지원한다 — 기본 목록엔 none이 빠져 있다.
                                .tokenEndpointAuthenticationMethod("none")))
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestConverter(new DefaultScopeAuthorizationRequestConverter())
                                .authenticationProviders(providers -> providers.stream()
                                        .filter(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::isInstance)
                                        .map(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::cast)
                                        .forEach(provider -> provider.setAuthenticationValidator(
                                                new McpAuthorizationRequestValidator(mcpOAuthProperties.resourceUrl())))))
                        // refresh 교환 요청에는 code_verifier가 없어 기본 공개 클라이언트 컨버터가 받지 않는다 — 그보다 먼저 보도록 index 0에 넣는다.
                        .clientAuthentication(client -> client
                                .authenticationConverters(converters -> converters.add(0, new PublicClientRefreshTokenAuthenticationConverter()))
                                .authenticationProviders(providers -> providers.add(0, new PublicClientRefreshTokenAuthenticationProvider(registeredClientRepository)))))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // GET /oauth2/authorize 미인증을 SPA로 리다이렉트하는 처리는 B3에서 덧붙인다 — 지금은 401.
                .exceptionHandling(exception -> exception.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
