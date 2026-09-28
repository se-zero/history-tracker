package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 종단(§9 리스크 검증) — JdbcOAuth2AuthorizationService의 Jackson 직렬화 round-trip과 aud 커스터마이저를
// refresh_token 교환 경로에서 먼저 검증한다. authorization_code 발급은 거치지 않고, 저장소에 직접 시드한
// refresh 토큰으로 /oauth2/token을 호출한다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("공개 클라이언트 refresh_token 교환: 발급·회전·거부")
class PublicClientRefreshTokenFlowTest {

    private static final String ISSUER = "http://localhost:5173";
    private static final String MCP_AUDIENCE = ISSUER + "/mcp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("seed로 교환 → 200 발급·회전, 소비한 seed 재사용은 invalid_grant, 회전된 값은 다시 회전 가능")
    void refreshTokenExchangeRotatesAndInvalidatesConsumedToken() throws Exception {
        Seed seed = seedClientWithRefreshToken();

        // a. 최초 seed로 교환 → 200, access/refresh 신규 발급(aud·sub·scope 클레임 검증)
        MvcResult first = mockMvc.perform(tokenRequest(seed.clientId(), seed.refreshTokenValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("mcp:query"))
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.refresh_token").exists())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("expires_in").asLong()).isBetween(3500L, 3600L);
        String firstAccessToken = firstBody.get("access_token").asText();
        String firstRefreshToken = firstBody.get("refresh_token").asText();
        assertThat(firstRefreshToken).isNotEqualTo(seed.refreshTokenValue());

        SignedJWT signedJwt = SignedJWT.parse(firstAccessToken);
        assertThat(signedJwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(signedJwt.getJWTClaimsSet().getIssuer()).isEqualTo(ISSUER);
        assertThat(signedJwt.getJWTClaimsSet().getAudience()).contains(MCP_AUDIENCE);
        assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo(seed.userId().toString());
        // Spring JwtGenerator는 scope를 Set으로 넣어 JWT에는 배열(["mcp:query"])로 실린다.
        assertThat(signedJwt.getJWTClaimsSet().getStringListClaim("scope")).contains("mcp:query");

        // b. 이미 소비한 seed로 재교환 → 400 invalid_grant(reuseRefreshTokens=false 회전으로 무효화됐어야 한다)
        mockMvc.perform(tokenRequest(seed.clientId(), seed.refreshTokenValue()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        // c. 회전된 refresh_token으로 재교환 → 200(연쇄 회전이 계속된다)
        mockMvc.perform(tokenRequest(seed.clientId(), firstRefreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.refresh_token").exists());
    }

    @Test
    @DisplayName("client_secret을 함께 보내면 공개 클라이언트라도 401 invalid_client")
    void refreshTokenExchangeRejectsRequestWithClientSecret() throws Exception {
        Seed seed = seedClientWithRefreshToken();

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", seed.refreshTokenValue())
                        .param("client_id", seed.clientId())
                        .param("client_secret", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    @DisplayName("등록되지 않은 client_id → 401 invalid_client")
    void refreshTokenExchangeRejectsUnregisteredClient() throws Exception {
        mockMvc.perform(tokenRequest("no-such-client-" + UUID.randomUUID(), "irrelevant-refresh-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    // ── 헬퍼 ──

    private MockHttpServletRequestBuilder tokenRequest(String clientId, String refreshTokenValue) {
        return post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshTokenValue)
                .param("client_id", clientId);
    }

    private Seed seedClientWithRefreshToken() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("flow-public-client-" + UUID.randomUUID())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost/callback")
                .scope("mcp:query")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        registeredClientRepository.save(client);

        UUID userId = UUID.randomUUID();
        OAuth2RefreshToken seedToken = new OAuth2RefreshToken(
                "seed-" + UUID.randomUUID(), Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(userId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("mcp:query"))
                .attribute(Principal.class.getName(), principal)
                .refreshToken(seedToken)
                .build();
        authorizationService.save(authorization);

        return new Seed(client.getClientId(), seedToken.getTokenValue(), userId);
    }

    private record Seed(String clientId, String refreshTokenValue, UUID userId) {
    }
}
