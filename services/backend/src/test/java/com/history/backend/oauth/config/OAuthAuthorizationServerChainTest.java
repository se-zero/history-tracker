package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import com.history.backend.oauth.service.OAuthJwkProvider;
import com.history.backend.security.JwtTokenService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// OAuth 2.1 인가 서버(/oauth2/*, /.well-known/*)와 MCP 리소스 서버(/mcp) 필터 체인이
// 기존 SPA HS256 체인(JwtAuthenticationFilter)과 분리돼 공존하는지 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OAuth 인가 서버·MCP 리소스 서버: 메타데이터·JWKS·토큰 검증 체인")
class OAuthAuthorizationServerChainTest {

    private static final String ISSUER = "http://localhost:5173";
    private static final String MCP_AUDIENCE = ISSUER + "/mcp";
    private static final String RESOURCE_METADATA_URL = ISSUER + "/.well-known/oauth-protected-resource/mcp";
    private static final String MCP_INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OAuthJwkProvider jwkProvider;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Test
    @DisplayName("인가 서버 메타데이터 — 필수 필드 노출")
    void authorizationServerMetadataExposesRequiredFields() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
                .andExpect(jsonPath("$.code_challenge_methods_supported", Matchers.hasItem("S256")))
                .andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/oauth2/jwks"))
                .andExpect(jsonPath("$.scopes_supported", Matchers.hasItem("mcp:query")))
                .andExpect(jsonPath("$.grant_types_supported", Matchers.hasItems("authorization_code", "refresh_token")))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported", Matchers.hasItem("none")))
                .andExpect(jsonPath("$.revocation_endpoint_auth_methods_supported", Matchers.hasItem("none")))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    @Test
    @DisplayName("JWKS 엔드포인트 — 공개키만 노출(개인키 d 없음)")
    void jwksEndpointExposesPublicKeyOnly() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value(jwkProvider.rsaKey().getKeyID()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    @DisplayName("토큰 없이 /mcp 요청 → 401, WWW-Authenticate에 resource_metadata·scope 명시")
    void mcpEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer resource_metadata=\"" + RESOURCE_METADATA_URL + "\", scope=\"mcp:query\""));
    }

    @Test
    @DisplayName("형식이 아닌 토큰으로 /mcp 요청 → 401 invalid_token")
    void mcpEndpointRejectsMalformedToken() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        Matchers.allOf(
                                Matchers.startsWith("Bearer "),
                                Matchers.containsString("error=\"invalid_token\""),
                                Matchers.containsString("resource_metadata=\"" + RESOURCE_METADATA_URL + "\""))));
    }

    @Test
    @DisplayName("SPA HS256 액세스 토큰으로 /mcp 요청 → 401 invalid_token(리소스 서버는 RS256만 인정)")
    void mcpEndpointRejectsSpaHs256Token() throws Exception {
        String hs256Token = jwtTokenService.issueAccessToken(UUID.randomUUID());

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hs256Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("error=\"invalid_token\"")));
    }

    // 이 케이스는 기존 JwtAuthenticationFilter의 서블릿 자동 등록이 해제됐음도 함께 증명한다
    // (해제 전엔 보안 체인 통과 뒤 그 필터가 RS256을 HS256으로 재검사해 401을 냈다).
    @Test
    @DisplayName("유효한 RS256 토큰은 인증·인가를 통과한다(MockMvc는 /mcp 서블릿에 닿지 않아 통과한 요청은 404로 끝난다)")
    void mcpEndpointAcceptsValidRsaTokenPastAuthenticationAndAuthorization() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + withGrantRow(validRsaToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    @DisplayName("aud가 리소스와 다른 RS256 토큰 → 401 invalid_token")
    void rejectsRsaTokenWithWrongAudience() throws Exception {
        Instant now = Instant.now();
        // 연결 행을 심어 둔다 — 행이 없으면 입구 검증기만으로도 401이라 aud 검사가 빠져도 이 테스트가 통과한다.
        String token = withGrantRow(rsaToken(ISSUER + "/other", "mcp:query", now, now.plus(Duration.ofHours(1))));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("error=\"invalid_token\"")));
    }

    @Test
    @DisplayName("만료된 RS256 토큰 → 401 invalid_token")
    void rejectsExpiredRsaToken() throws Exception {
        Instant now = Instant.now();
        // 연결 행을 심어 둔다 — 행이 없으면 만료 검사가 빠져도 입구 검증기 때문에 통과한다.
        String token = withGrantRow(
                rsaToken(MCP_AUDIENCE, "mcp:query", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("error=\"invalid_token\"")));
    }

    @Test
    @DisplayName("scope 클레임 없는 RS256 토큰 → 403 insufficient_scope")
    void rejectsRsaTokenWithoutScopeClaim() throws Exception {
        Instant now = Instant.now();
        String token = withGrantRow(rsaToken(MCP_AUDIENCE, null, now, now.plus(Duration.ofHours(1))));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_INITIALIZE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("insufficient_scope")));
    }

    @Test
    @DisplayName("보호 리소스 메타데이터 — 정식 경로와 리소스별 경로 모두 200")
    void protectedResourceMetadataServedAtBothPaths() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(MCP_AUDIENCE))
                .andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
                .andExpect(jsonPath("$.scopes_supported[0]").value("mcp:query"))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                // mTLS 인증서에 묶인 토큰은 지원하지 않는다 — Spring 기본값(true)이 그대로 나가면 틀린 광고다.
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));

        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(MCP_AUDIENCE))
                .andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
                .andExpect(jsonPath("$.scopes_supported[0]").value("mcp:query"))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    @Test
    @DisplayName("회귀 — SPA 체인 /api/v1/me는 토큰 없으면 401 Authentication is required.")
    void meEndpointRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    @DisplayName("회귀 — SPA 체인 /api/v1/me는 RS256 토큰을 여전히 거부한다(HS256만 인정)")
    void meEndpointRejectsRsaToken() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validRsaToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid access token."));
    }

    // ── 헬퍼 — 리소스 서버가 검증할 RS256 토큰을 provider의 실제 서명키로 직접 서명한다 ──

    private String validRsaToken() throws Exception {
        Instant now = Instant.now();
        return rsaToken(MCP_AUDIENCE, "mcp:query", now, now.plus(Duration.ofHours(1)));
    }

    // 입구 검증기는 토큰 값의 연결 행(oauth2_authorization)이 있어야 통과시키므로, 직접 서명한 토큰에 같은 값의 행을 심는다.
    private String withGrantRow(String token) throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("chain-test-client-" + UUID.randomUUID())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/callback")
                .scope("mcp:query")
                .build();
        registeredClientRepository.save(client);
        String scope = claims.getStringClaim("scope");
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, token,
                claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant(),
                scope == null ? Set.of() : Set.of(scope));
        authorizationService.save(OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(claims.getSubject())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(accessToken)
                .build());
        return token;
    }

    private String rsaToken(String audience, String scope, Instant issuedAt, Instant expiresAt) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt));
        if (scope != null) {
            claims.claim("scope", scope);
        }
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(jwkProvider.rsaKey().getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims.build());
        signedJwt.sign(new RSASSASigner(jwkProvider.rsaKey()));
        return signedJwt.serialize();
    }
}
