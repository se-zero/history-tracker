package com.history.backend.oauth.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.oauth.OAuthRateLimitProperties;
import com.history.backend.oauth.security.ConsentTicketAuthenticationFilter;
import com.history.backend.oauth.security.DefaultScopeAuthorizationRequestConverter;
import com.history.backend.oauth.security.McpAuthorizationRequestValidator;
import com.history.backend.oauth.security.OAuthRateLimitFilter;
import com.history.backend.oauth.security.OAuthRateLimiter;
import com.history.backend.oauth.security.PublicClientRefreshTokenAuthenticationConverter;
import com.history.backend.oauth.security.PublicClientRefreshTokenAuthenticationProvider;
import com.history.backend.oauth.security.PublicClientRefreshTokenGenerator;
import com.history.backend.oauth.security.PublicClientRegistrationConverter;
import com.history.backend.oauth.security.SpaConsentRedirectEntryPoint;
import com.history.backend.oauth.service.CimdDocumentFetcher;
import com.history.backend.oauth.service.CimdRegisteredClientRepository;
import com.history.backend.oauth.service.ConsentTicketService;
import com.history.backend.oauth.service.McpRegisteredClientPolicy;
import com.history.backend.oauth.service.OAuthJwkProvider;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;

// MCP 클라이언트용 OAuth 2.1 인가 서버(/oauth2/**, /.well-known/oauth-authorization-server) 설정
@Configuration
@RequiredArgsConstructor
public class OAuthAuthorizationServerConfig {

    private final McpOAuthProperties mcpOAuthProperties;
    private final ConsentTicketService consentTicketService;
    private final OAuthRateLimiter rateLimiter;
    private final OAuthRateLimitProperties rateLimitProperties;

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer(mcpOAuthProperties.issuer()).build();
    }

    // Boot 자동구성이 만드는 임시 키를 대체한다 — 다중 인스턴스·재기동에도 같은 키를 써야 한다.
    @Bean
    JWKSource<SecurityContext> jwkSource(OAuthJwkProvider oAuthJwkProvider) {
        return oAuthJwkProvider.jwkSource();
    }

    // CIMD client_id(https URL)는 사전 등록 없이 오므로 JDBC 저장소 앞에 그림자 upsert 래퍼를 둔다.
    @Bean
    RegisteredClientRepository registeredClientRepository(
            JdbcTemplate jdbcTemplate, CimdDocumentFetcher fetcher, McpRegisteredClientPolicy policy) {
        return new CimdRegisteredClientRepository(
                new JdbcRegisteredClientRepository(jdbcTemplate), fetcher, policy, Clock.systemUTC());
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

    private AuthenticationEntryPoint consentAwareEntryPoint() {
        return new DelegatingAuthenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                List.of(new RequestMatcherEntry<>(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/oauth2/authorize"),
                        new SpaConsentRedirectEntryPoint(mcpOAuthProperties))));
    }

    @Bean
    @Order(1)
    SecurityFilterChain oauthAuthorizationServerChain(
            HttpSecurity http, RegisteredClientRepository registeredClientRepository, McpRegisteredClientPolicy policy)
            throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, server -> server
                        .authorizationServerMetadataEndpoint(metadata -> metadata.authorizationServerMetadataCustomizer(builder -> builder
                                // CIMD(Client ID Metadata Document) 지원 신호 — Spring에 전용 API가 없어 커스텀 클레임으로 알린다.
                                .claim("client_id_metadata_document_supported", true)
                                .scope("mcp:query")
                                // 공개 클라이언트(PKCE, client_secret 없음)만 지원한다 — 기본 목록엔 none이 빠져 있다.
                                .tokenEndpointAuthenticationMethod("none")
                                // Spring 기본값은 true인데 mTLS 인증서에 묶인 토큰은 지원하지 않는다 — 틀린 광고를 끈다.
                                .tlsClientCertificateBoundAccessTokens(false)))
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
                                .authenticationProviders(providers -> providers.add(0, new PublicClientRefreshTokenAuthenticationProvider(registeredClientRepository))))
                        // RFC 7591 DCR 폴백 — CIMD를 못 하는 클라이언트용. 공개 클라이언트(none)만 받는다.
                        .clientRegistrationEndpoint(registration -> registration
                                .openRegistrationAllowed(true)
                                .authenticationProviders(providers -> providers.stream()
                                        .filter(OAuth2ClientRegistrationAuthenticationProvider.class::isInstance)
                                        .map(OAuth2ClientRegistrationAuthenticationProvider.class::cast)
                                        .forEach(provider -> provider.setRegisteredClientConverter(new PublicClientRegistrationConverter(policy))))))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // DCR 필터도 authorize 필터처럼 AuthorizationFilter 뒤에 붙어 있어 openRegistrationAllowed(true)와
                        // 무관하게 anyRequest().authenticated()에 걸려 401이 난다(실기동에서 확인된 사실).
                        .requestMatchers(HttpMethod.POST, "/oauth2/register").permitAll()
                        .anyRequest().authenticated())
                // 클라이언트 조회(CIMD 문서 fetch·행 생성)와 티켓 소비보다 앞에서 IP별 상한을 건다.
                // 체인의 securityMatcher가 이미 인가 서버 주소만 고르므로 경로는 다시 판정하지 않는다.
                .addFilterBefore(new OAuthRateLimitFilter(rateLimiter, rateLimitProperties, AnyRequestMatcher.INSTANCE), SecurityContextHolderFilter.class)
                // 티켓 인증은 SecurityContextHolderFilter가 컨텍스트를 비운 뒤에 세팅해야 살아남는다.
                .addFilterAfter(new ConsentTicketAuthenticationFilter(consentTicketService), SecurityContextHolderFilter.class)
                // 미인증 GET /oauth2/authorize만 SPA 허용 화면으로 보내고, 나머지 요청은 기존처럼 401이다.
                .exceptionHandling(exception -> exception.authenticationEntryPoint(consentAwareEntryPoint()));
        return http.build();
    }
}
