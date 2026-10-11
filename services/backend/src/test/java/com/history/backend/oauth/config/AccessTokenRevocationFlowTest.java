package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.oauth.service.ConsentTicketService;
import com.history.backend.oauth.service.OAuthGrantService;
import jakarta.servlet.http.Cookie;
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
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

// 종단 — 실제 발급 경로(티켓 → authorize → token)로 얻은 RS256 access 토큰이 /mcp 입구에서
// 연결 철회·회전·코드 재사용에 즉시 반응하는지 확인한다. MockMvc는 DispatcherServlet만 구동해 /mcp 서블릿에 닿지 않으므로 "통과"는 401/403이 아님으로 판정한다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("access 토큰 철회 종단: 연결 행이 사라지거나 무효 표시되면 /mcp는 즉시 401")
class AccessTokenRevocationFlowTest {

    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String TICKET_COOKIE = "wc_oauth_ticket";
    private static final String REDIRECT_URI = "http://localhost:53421/callback";
    private static final String MCP_INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private ConsentTicketService consentTicketService;

    @Autowired
    private OAuthGrantService oAuthGrantService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("발급 직후 access 토큰은 /mcp 인증·인가를 통과한다")
    void freshlyIssuedAccessTokenPassesMcpGate() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();

        Tokens tokens = connect(userId, client);

        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);
    }

    @Test
    @DisplayName("연결을 철회하면 같은 access 토큰은 401 invalid_token")
    void revokedGrantAccessTokenIsRejected() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens tokens = connect(userId, client);
        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);

        oAuthGrantService.revoke(userId, client.getId());

        expectInvalidToken(tokens.accessToken());
    }

    @Test
    @DisplayName("refresh로 갱신하면 옛 access 토큰은 401, 새 access 토큰은 통과")
    void refreshInvalidatesOldAccessTokenAndKeepsNewOne() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens first = connect(userId, client);

        MvcResult refreshed = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", first.refreshToken())
                        .param("client_id", client.getClientId()))
                .andExpect(status().isOk())
                .andReturn();
        String newAccessToken = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .get("access_token").asText();
        assertThat(newAccessToken).isNotEqualTo(first.accessToken());

        expectInvalidToken(first.accessToken());
        assertThat(mcpStatus(newAccessToken)).isNotIn(401, 403);
    }

    @Test
    @DisplayName("같은 인가 코드를 두 번 교환해 연결이 무효화되면 첫 교환의 access 토큰은 401")
    void authorizationCodeReuseInvalidatesIssuedAccessToken() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        String code = authorizeForCode(userId, client);
        Tokens first = exchangeCode(code, client);
        assertThat(mcpStatus(first.accessToken())).isNotIn(401, 403);

        mockMvc.perform(codeExchangeRequest(code, client))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        expectInvalidToken(first.accessToken());
    }

    @Test
    @DisplayName("탈퇴 경로(revokeAll) 뒤에는 그 사용자의 access 토큰이 401")
    void revokeAllRejectsAccessToken() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens tokens = connect(userId, client);
        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);

        oAuthGrantService.revokeAll(userId);

        expectInvalidToken(tokens.accessToken());
    }

    @Test
    @DisplayName("공개 클라이언트가 client_id만으로 refresh 토큰을 폐기하면 200, 연결 행이 지워져 access 401·refresh 교환 invalid_grant")
    void revokingRefreshTokenRemovesConnection() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens tokens = connect(userId, client);
        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);

        mockMvc.perform(revokeRequest(tokens.refreshToken(), "refresh_token", client))
                .andExpect(status().isOk());

        expectInvalidToken(tokens.accessToken());
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.refreshToken())
                        .param("client_id", client.getClientId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(authorizationService.findByToken(tokens.refreshToken(), null)).isNull();
    }

    @Test
    @DisplayName("access 토큰을 폐기해도 200, 연결 행 전체(access·refresh)가 지워진다")
    void revokingAccessTokenRemovesWholeConnection() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens tokens = connect(userId, client);

        mockMvc.perform(revokeRequest(tokens.accessToken(), "access_token", client))
                .andExpect(status().isOk());

        assertThat(authorizationService.findByToken(tokens.accessToken(), null)).isNull();
        assertThat(authorizationService.findByToken(tokens.refreshToken(), null)).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 토큰을 폐기하면 200, 기존 연결은 그대로")
    void revokingUnknownTokenKeepsExistingConnection() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens tokens = connect(userId, client);

        mockMvc.perform(revokeRequest("unknown-" + UUID.randomUUID(), "refresh_token", client))
                .andExpect(status().isOk());

        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);
        assertThat(authorizationService.findByToken(tokens.refreshToken(), null)).isNotNull();
    }

    @Test
    @DisplayName("다른 공개 클라이언트의 client_id로 폐기하면 400 invalid_client(폐기 엔드포인트 실패 핸들러는 항상 400), 연결 행은 그대로")
    void revokingWithOtherClientIdIsRejected() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient owner = registerPublicClient();
        RegisteredClient other = registerPublicClient();
        Tokens tokens = connect(userId, owner);

        mockMvc.perform(revokeRequest(tokens.refreshToken(), "refresh_token", other))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);
        assertThat(authorizationService.findByToken(tokens.refreshToken(), null)).isNotNull();
    }

    @Test
    @DisplayName("client_secret을 함께 보내면 401 invalid_client(현행 유지), 연결 행은 그대로")
    void revokingWithClientSecretIsRejected() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens tokens = connect(userId, client);

        mockMvc.perform(revokeRequest(tokens.refreshToken(), "refresh_token", client)
                        .param("client_secret", "should-not-happen"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        assertThat(mcpStatus(tokens.accessToken())).isNotIn(401, 403);
        assertThat(authorizationService.findByToken(tokens.refreshToken(), null)).isNotNull();
    }

    @Test
    @DisplayName("같은 사용자·클라이언트의 연결이 둘일 때 하나를 폐기해도 다른 연결의 access 토큰은 통과")
    void revokingOneConnectionKeepsTheOtherOfSameUserAndClient() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        Tokens first = connect(userId, client);
        Tokens second = connect(userId, client);

        mockMvc.perform(revokeRequest(first.refreshToken(), "refresh_token", client))
                .andExpect(status().isOk());

        expectInvalidToken(first.accessToken());
        assertThat(mcpStatus(second.accessToken())).isNotIn(401, 403);
    }

    // ── 헬퍼 ──

    private MockHttpServletRequestBuilder revokeRequest(String token, String tokenTypeHint, RegisteredClient client) {
        return post("/oauth2/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", token)
                .param("token_type_hint", tokenTypeHint)
                .param("client_id", client.getClientId());
    }

    private int mcpStatus(String accessToken) throws Exception {
        return postMcp(accessToken).andReturn().getResponse().getStatus();
    }

    private void expectInvalidToken(String accessToken) throws Exception {
        postMcp(accessToken)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("error=\"invalid_token\"")));
    }

    private ResultActions postMcp(String accessToken) throws Exception {
        return mockMvc.perform(post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MCP_INITIALIZE_BODY));
    }

    private UUID saveActiveUser() {
        String providerUserId = UUID.randomUUID().toString();
        User user = userRepository.save(
                new User("github", providerUserId, providerUserId + "@example.com", "Revocation Flow User", null));
        return user.getId();
    }

    // 티켓 → authorize → token 교환까지 끝내 access·refresh 토큰을 돌려준다
    private Tokens connect(UUID userId, RegisteredClient client) throws Exception {
        return exchangeCode(authorizeForCode(userId, client), client);
    }

    private Tokens exchangeCode(String code, RegisteredClient client) throws Exception {
        MvcResult tokenResult = mockMvc.perform(codeExchangeRequest(code, client))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        return new Tokens(body.get("access_token").asText(), body.get("refresh_token").asText());
    }

    private MockHttpServletRequestBuilder codeExchangeRequest(String code, RegisteredClient client) {
        return post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("client_id", client.getClientId())
                .param("code_verifier", CODE_VERIFIER);
    }

    private String authorizeForCode(UUID userId, RegisteredClient client) throws Exception {
        String rawQuery = authorizeQuery(client.getClientId(), UUID.randomUUID().toString());
        String ticket = consentTicketService.issue(userId, rawQuery);
        MvcResult authorizeResult = mockMvc.perform(get(URI.create("/oauth2/authorize?" + rawQuery))
                        .cookie(new Cookie(TICKET_COOKIE, ticket)))
                .andExpect(status().isFound())
                .andReturn();
        String code = UriComponentsBuilder.fromUriString(authorizeResult.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();
        return code;
    }

    private String authorizeQuery(String clientId, String state) {
        return "response_type=code&client_id=" + clientId
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A53421%2Fcallback"
                + "&scope=mcp%3Aquery&state=" + state
                + "&code_challenge=" + codeChallenge()
                + "&code_challenge_method=S256"
                + "&resource=http%3A%2F%2Flocalhost%3A5173%2Fmcp";
    }

    private String codeChallenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(CODE_VERIFIER.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RegisteredClient registerPublicClient() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("revocation-flow-client-" + UUID.randomUUID())
                .clientName("Revocation Flow Client")
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
        return client;
    }

    private record Tokens(String accessToken, String refreshToken) {
    }
}
