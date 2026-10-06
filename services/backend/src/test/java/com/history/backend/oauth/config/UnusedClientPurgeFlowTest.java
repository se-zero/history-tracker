package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

// 종단(H2) — 연결 없는 오래된 앱 행의 정리가 같은 SQL로 H2에서도 돌고, 연결이 남은 앱은 지우지 않으며,
// 겹침으로 앱 행만 사라져도 /mcp 입구가 500이 아니라 401로 닫히는지 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("안 쓰는 앱 정리 종단: 연결 없는 오래된 앱만 지우고, 앱 행이 없어도 /mcp는 401")
class UnusedClientPurgeFlowTest {

    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String TICKET_COOKIE = "wc_oauth_ticket";
    private static final String REDIRECT_URI = "http://localhost:53421/callback";
    private static final String MCP_INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private ConsentTicketService consentTicketService;

    @Autowired
    private OAuthGrantService oAuthGrantService;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("DCR로 등록하고 8일 지난 연결 없는 앱은 지우고, 방금 등록한 앱은 남긴다")
    void purgeDeletesOldDcrClientAndKeepsRecentOne() throws Exception {
        String oldClientId = registerViaDcr();
        String recentClientId = registerViaDcr();
        backdateIssuedAt(oldClientId, Duration.ofDays(8));

        oAuthGrantService.purgeUnusedClients();

        assertThat(clientRowCount(oldClientId)).isZero();
        assertThat(clientRowCount(recentClientId)).isEqualTo(1);
    }

    @Test
    @DisplayName("연결이 있는 앱은 8일 지나도 남고 그 access 토큰은 /mcp 입구를 계속 통과한다")
    void purgeKeepsOldClientWithAuthorizationAndItsAccessTokenStillPassesMcpGate() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        String accessToken = connect(userId, client);
        backdateIssuedAt(client.getClientId(), Duration.ofDays(8));

        oAuthGrantService.purgeUnusedClients();

        assertThat(clientRowCount(client.getClientId())).isEqualTo(1);
        assertThat(mcpStatus(accessToken)).isNotIn(401, 403, 500);
    }

    @Test
    @DisplayName("연결은 남았는데 앱 행만 사라져도(겹침) /mcp는 500이 아니라 끊긴 토큰과 같은 401")
    void missingRegisteredClientRowIsRejectedLikeRevokedToken() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient overlapped = registerPublicClient();
        String overlappedToken = connect(userId, overlapped);
        RegisteredClient revoked = registerPublicClient();
        String revokedToken = connect(userId, revoked);
        oAuthGrantService.revoke(userId, revoked.getId());
        String revokedHeader = postMcp(revokedToken)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);

        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", overlapped.getId());

        postMcp(overlappedToken)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("error=\"invalid_token\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, revokedHeader));
    }

    // ── 헬퍼 ──

    private String registerViaDcr() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Purge Flow DCR Client",
                                  "redirect_uris": ["http://localhost/callback"],
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": ["authorization_code", "refresh_token"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("client_id").asText();
    }

    private void backdateIssuedAt(String clientId, Duration age) {
        jdbcTemplate.update("UPDATE oauth2_registered_client SET client_id_issued_at = ? WHERE client_id = ?",
                Timestamp.from(Instant.now().minus(age)), clientId);
    }

    private int clientRowCount(String clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = ?", Integer.class, clientId);
    }

    private int mcpStatus(String accessToken) throws Exception {
        return postMcp(accessToken).andReturn().getResponse().getStatus();
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
                new User("github", providerUserId, providerUserId + "@example.com", "Purge Flow User", null));
        return user.getId();
    }

    // 티켓 → authorize → token 교환까지 끝내 access 토큰을 돌려준다
    private String connect(UUID userId, RegisteredClient client) throws Exception {
        String rawQuery = authorizeQuery(client.getClientId(), UUID.randomUUID().toString());
        String ticket = consentTicketService.issue(userId, rawQuery);
        MvcResult authorizeResult = mockMvc.perform(get(URI.create("/oauth2/authorize?" + rawQuery))
                        .cookie(new Cookie(TICKET_COOKIE, ticket)))
                .andExpect(status().isFound())
                .andReturn();
        String code = UriComponentsBuilder.fromUriString(authorizeResult.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", client.getClientId())
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        return body.get("access_token").asText();
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
                .clientId("purge-flow-client-" + UUID.randomUUID())
                .clientName("Purge Flow Client")
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
}
