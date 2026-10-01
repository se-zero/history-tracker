package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.oauth.service.OAuthConsentService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

// B(동의 API) 종단 — 실제 빈(OAuthConsentService·ConsentTicketService·인가 서버 체인)을 엮어
// 미인증 authorize → preview → decide → 티켓 쿠키로 authorize → code → token 까지 이어지는지,
// 그리고 화면에 보이는 내용(preview)과 실제 발급(token)이 일치하는지 확인한다.
// 요청 쿼리는 원문을 보존해야 티켓 해시가 맞으므로 get(URI.create(...))로 만든다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("동의 흐름 종단: authorize → preview → decide → authorize → token")
class ConsentFlowEndToEndTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String TICKET_COOKIE = "wc_oauth_ticket";
    private static final String CONSENT_URL = "http://localhost:5173/oauth/consent";
    private static final String BEARER = "Bearer access-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
        when(userService.getActiveUser(USER_ID))
                .thenReturn(new User("github", "12345", "octocat@example.com", "Octocat", null));
    }

    @Test
    @DisplayName("허용 종단 — 302 허용 화면 → preview → decide → 쿠키로 authorize 302(code·state) → token 200, sub는 JWT 사용자")
    void approveFlowIssuesTokenForJwtUser() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1", "mcp%3Aquery");

        // 1) 로그인 정보 없는 authorize → SPA 허용 화면으로 원본 쿼리 그대로 302
        MvcResult redirect = mockMvc.perform(get(URI.create("/oauth2/authorize?" + rawQuery)))
                .andExpect(status().isFound())
                .andReturn();
        String location = redirect.getResponse().getHeader("Location");
        assertThat(location).isEqualTo(CONSENT_URL + "?" + rawQuery);
        String consentQuery = location.substring(location.indexOf('?') + 1);

        // 2) 화면이 같은 쿼리로 preview
        mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + consentQuery))
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientName").value("Consent E2E Client"))
                .andExpect(jsonPath("$.redirectUri").value("http://localhost:53421/callback"))
                .andExpect(jsonPath("$.redirectHost").value("localhost"))
                .andExpect(jsonPath("$.loopback").value(true))
                .andExpect(jsonPath("$.scopes[0]").value("mcp:query"))
                .andExpect(jsonPath("$.scopes.length()").value(1));

        // 3) 허용 → redirectTo는 원본 쿼리 그대로, 티켓은 쿠키로만
        MvcResult decide = decide(consentQuery, true)
                .andExpect(status().isOk())
                .andReturn();
        String redirectTo = objectMapper.readTree(decide.getResponse().getContentAsString()).get("redirectTo").asText();
        assertThat(redirectTo).isEqualTo("/oauth2/authorize?" + consentQuery);
        String ticket = ticketFrom(decide);
        assertThat(ticket).isNotBlank();

        // 4) 받은 쿠키로 authorize → 앱의 redirect_uri로 code·state
        MvcResult authorize = mockMvc.perform(get(URI.create(redirectTo)).cookie(new Cookie(TICKET_COOKIE, ticket)))
                .andExpect(status().isFound())
                .andReturn();
        String callback = authorize.getResponse().getHeader("Location");
        assertThat(callback).startsWith("http://localhost:53421/callback?");
        var params = UriComponentsBuilder.fromUriString(callback).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("s1");
        String code = params.getFirst("code");
        assertThat(code).isNotBlank();

        // 5) code → token, access 토큰 sub는 JWT 사용자
        JWTClaimsSet claims = exchangeCode(clientId, code);
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
    }

    @Test
    @DisplayName("scope 없는 요청 — preview의 scopes와 발급된 access 토큰의 scope 클레임이 같이 mcp:query")
    void missingScopeIsConsistentBetweenPreviewAndIssuedToken() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1", null);

        mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + rawQuery))
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes[0]").value("mcp:query"))
                .andExpect(jsonPath("$.scopes.length()").value(1));

        MvcResult decide = decide(rawQuery, true).andExpect(status().isOk()).andReturn();
        String redirectTo = objectMapper.readTree(decide.getResponse().getContentAsString()).get("redirectTo").asText();
        MvcResult authorize = mockMvc.perform(get(URI.create(redirectTo)).cookie(new Cookie(TICKET_COOKIE, ticketFrom(decide))))
                .andExpect(status().isFound())
                .andReturn();
        String code = UriComponentsBuilder.fromUriString(authorize.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("code");

        JWTClaimsSet claims = exchangeCode(clientId, code);
        assertThat(String.valueOf(claims.getClaim("scope"))).contains("mcp:query");
    }

    @Test
    @DisplayName("거부 종단 — redirectTo는 앱 주소 + error=access_denied&state, 티켓 쿠키 없음")
    void denyFlowRedirectsWithAccessDeniedAndNoCookie() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1", "mcp%3Aquery");

        MvcResult decide = decide(rawQuery, false).andExpect(status().isOk()).andReturn();

        JsonNode body = objectMapper.readTree(decide.getResponse().getContentAsString());
        assertThat(body.get("redirectTo").asText())
                .isEqualTo("http://localhost:53421/callback?error=access_denied&state=s1");
        assertThat(decide.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .noneSatisfy(header -> assertThat(header).contains(TICKET_COOKIE));
    }

    @Test
    @DisplayName("등록 안 된 client_id로 preview → 400, 등록되지 않은 앱 문구")
    void previewUnknownClientIsBadRequest() throws Exception {
        String rawQuery = authorizeQuery("no-such-client-" + UUID.randomUUID(), "s1", "mcp%3Aquery");

        mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + rawQuery))
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(OAuthConsentService.MESSAGE_UNKNOWN_CLIENT));
    }

    // ── 헬퍼 ──

    private ResultActions decide(String query, boolean approved) throws Exception {
        return mockMvc.perform(post("/api/v1/oauth/consent")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "approved", approved))));
    }

    // Set-Cookie 헤더에서 티켓 쿠키 값을 꺼낸다
    private String ticketFrom(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(TICKET_COOKIE + "="))
                .map(header -> header.substring((TICKET_COOKIE + "=").length(), header.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ticket cookie not set"));
    }

    private JWTClaimsSet exchangeCode(String clientId, String code) throws Exception {
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "http://localhost:53421/callback")
                        .param("client_id", clientId)
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        return SignedJWT.parse(body.get("access_token").asText()).getJWTClaimsSet();
    }

    // scope가 null이면 scope 파라미터를 뺀다
    private String authorizeQuery(String clientId, String state, String encodedScope) {
        return "response_type=code&client_id=" + clientId
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A53421%2Fcallback"
                + (encodedScope == null ? "" : "&scope=" + encodedScope)
                + "&state=" + state
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

    private String registerPublicClient() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("consent-e2e-client-" + UUID.randomUUID())
                .clientName("Consent E2E Client")
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
        return client.getClientId();
    }
}
