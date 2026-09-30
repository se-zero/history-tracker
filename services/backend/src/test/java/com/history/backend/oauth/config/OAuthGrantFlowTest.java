package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.oauth.service.ConsentTicketService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

// C(연결된 앱) 종단 — 티켓 → authorize → token으로 실제 oauth2_authorization 행을 만든 뒤
// 실제 UserService(getActiveUser 게이트)·OAuthGrantService·SQL을 거쳐 목록·철회가 동작하는지,
// 철회가 refresh 교환을 막는지 확인한다. UserService는 mock이 아니라 실제 빈이다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("연결된 앱 종단: 연결 → 목록 → 철회 → refresh 거부")
class OAuthGrantFlowTest {

    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String TICKET_COOKIE = "wc_oauth_ticket";
    private static final String REDIRECT_URI = "http://localhost:53421/callback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private ConsentTicketService consentTicketService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("연결 후 목록 → 항목 1개, clientName·clientId는 저장한 앱, id는 앱의 내부 id, grantedAt 존재")
    void connectedAppAppearsInGrantList() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient("Grant Flow Client");

        connect(userId, client);

        mockMvc.perform(get("/api/v1/me/oauth-grants").header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(client.getId()))
                .andExpect(jsonPath("$[0].clientId").value(client.getClientId()))
                .andExpect(jsonPath("$[0].clientName").value("Grant Flow Client"))
                .andExpect(jsonPath("$[0].grantedAt").isNotEmpty());
    }

    @Test
    @DisplayName("같은 앱으로 두 번 연결해도 목록은 1항목")
    void connectingSameAppTwiceIsOneGrant() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient("Grant Flow Client");

        connect(userId, client);
        connect(userId, client);

        mockMvc.perform(get("/api/v1/me/oauth-grants").header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(client.getId()));
    }

    @Test
    @DisplayName("철회 → 204, 목록이 비고, 그 refresh 토큰 교환은 400 invalid_grant")
    void revokeRemovesGrantAndBlocksRefreshExchange() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient("Grant Flow Client");
        String refreshToken = connect(userId, client);

        mockMvc.perform(delete("/api/v1/me/oauth-grants/{id}", client.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/oauth-grants").header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(refreshRequest(client, refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @DisplayName("다른 사용자가 같은 id로 철회해도 204지만 원래 사용자의 연결은 남는다")
    void otherUserCannotRevokeSomeoneElsesGrant() throws Exception {
        UUID owner = saveActiveUser();
        UUID intruder = saveActiveUser();
        RegisteredClient client = registerPublicClient("Grant Flow Client");
        String refreshToken = connect(owner, client);

        mockMvc.perform(delete("/api/v1/me/oauth-grants/{id}", client.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(intruder)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/oauth-grants").header(HttpHeaders.AUTHORIZATION, bearerFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(client.getId()));
        mockMvc.perform(refreshRequest(client, refreshToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("코드만 발급되고 토큰으로 교환되지 않은 연결은 목록에 없다")
    void codeOnlyConnectionIsNotListed() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient("Grant Flow Client");

        authorizeForCode(userId, client);

        mockMvc.perform(get("/api/v1/me/oauth-grants").header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── 헬퍼 ──

    private UUID saveActiveUser() {
        String providerUserId = UUID.randomUUID().toString();
        User user = userRepository.save(
                new User("github", providerUserId, providerUserId + "@example.com", "Grant Flow User", null));
        return user.getId();
    }

    private String bearerFor(UUID userId) {
        String token = "access-token-" + userId;
        when(jwtTokenService.validateAccessToken(token)).thenReturn(new AuthenticatedUser(userId));
        return "Bearer " + token;
    }

    // 티켓 → authorize → token 교환까지 끝내 refresh 토큰 값을 돌려준다
    private String connect(UUID userId, RegisteredClient client) throws Exception {
        String code = authorizeForCode(userId, client);
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", client.getClientId())
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("refresh_token").asText();
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

    private MockHttpServletRequestBuilder refreshRequest(RegisteredClient client, String refreshToken) {
        return post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .param("client_id", client.getClientId());
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

    private RegisteredClient registerPublicClient(String clientName) {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("grant-flow-client-" + UUID.randomUUID())
                .clientName(clientName)
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
