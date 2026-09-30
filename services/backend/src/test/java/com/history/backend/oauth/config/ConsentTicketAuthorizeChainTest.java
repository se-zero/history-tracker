package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.oauth.service.ConsentTicketService;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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

// A(티켓 ↔ 인가 서버 연결) 종단 — 티켓 쿠키가 /oauth2/authorize를 그 사용자의 요청으로 통과시켜
// 인가 코드가 발급되고, 그 코드로 토큰까지 교환되는지 확인한다. 요청 쿼리는 원문을 보존해야 해시가 맞으므로
// get(URI.create(...))로 만든다(템플릿 방식은 재인코딩한다).
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("동의 티켓 ↔ /oauth2/authorize 체인")
class ConsentTicketAuthorizeChainTest {

    private static final String CONSENT_URL = "http://localhost:5173/oauth/consent";
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String TICKET_COOKIE = "wc_oauth_ticket";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private ConsentTicketService consentTicketService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("티켓 쿠키 + authorize → 루프백 포트가 달라도 302로 code·state 발급, 티켓 쿠키 삭제")
    void ticketCookieIssuesAuthorizationCodeAndClearsCookie() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1");
        String ticket = consentTicketService.issue(UUID.randomUUID(), rawQuery);

        MvcResult result = performAuthorize(rawQuery, ticket)
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://localhost:53421/callback?");
        AuthorizeParams params = queryParams(location);
        assertThat(params.code()).isNotBlank();
        assertThat(params.state()).isEqualTo("s1");
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header)
                        .startsWith(TICKET_COOKIE + "=;")
                        .contains("Path=/oauth2/authorize")
                        .contains("Max-Age=0"));
    }

    @Test
    @DisplayName("발급된 code로 /oauth2/token → 200, refresh_token 포함, access 토큰 sub는 티켓 사용자")
    void issuedCodeIsExchangedForTokensOfTicketUser() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1");
        UUID userId = UUID.randomUUID();
        String ticket = consentTicketService.issue(userId, rawQuery);
        MvcResult authorizeResult = performAuthorize(rawQuery, ticket)
                .andExpect(status().isFound())
                .andReturn();
        String code = queryParams(authorizeResult.getResponse().getHeader("Location")).code();

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "http://localhost:53421/callback")
                        .param("client_id", clientId)
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").exists())
                .andReturn();

        JsonNode body = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        SignedJWT accessToken = SignedJWT.parse(body.get("access_token").asText());
        assertThat(accessToken.getJWTClaimsSet().getSubject()).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("쿠키 없는 authorize → 302, Location은 허용 화면 + 원본 쿼리 그대로")
    void authorizeWithoutCookieRedirectsToConsentPage() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1");

        performAuthorize(rawQuery, null)
                .andExpect(status().isFound())
                .andExpect(mvcResult -> assertThat(mvcResult.getResponse().getHeader("Location"))
                        .isEqualTo(CONSENT_URL + "?" + rawQuery));
    }

    @Test
    @DisplayName("쿼리 A로 받은 티켓을 쿼리 B(state만 다름)에 쓰면 code 없이 허용 화면으로 302")
    void ticketForDifferentQueryRedirectsToConsentPage() throws Exception {
        String clientId = registerPublicClient();
        String queryA = authorizeQuery(clientId, "s1");
        String queryB = authorizeQuery(clientId, "s2");
        String ticket = consentTicketService.issue(UUID.randomUUID(), queryA);

        MvcResult result = performAuthorize(queryB, ticket)
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo(CONSENT_URL + "?" + queryB);
    }

    @Test
    @DisplayName("같은 티켓 재사용 → 첫 번째는 code, 두 번째는 허용 화면으로 302")
    void reusedTicketRedirectsToConsentPage() throws Exception {
        String clientId = registerPublicClient();
        String rawQuery = authorizeQuery(clientId, "s1");
        String ticket = consentTicketService.issue(UUID.randomUUID(), rawQuery);

        MvcResult first = performAuthorize(rawQuery, ticket)
                .andExpect(status().isFound())
                .andReturn();
        MvcResult second = performAuthorize(rawQuery, ticket)
                .andExpect(status().isFound())
                .andReturn();

        assertThat(first.getResponse().getHeader("Location")).startsWith("http://localhost:53421/callback?");
        assertThat(second.getResponse().getHeader("Location")).isEqualTo(CONSENT_URL + "?" + rawQuery);
    }

    @Test
    @DisplayName("등록 안 된 client_id는 쿠키가 없어도 SPA로 보내지 않고 400")
    void unknownClientIdIsBadRequestNotRedirected() throws Exception {
        String rawQuery = authorizeQuery("no-such-client-" + UUID.randomUUID(), "s1");

        performAuthorize(rawQuery, null)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("쿠키 없는 POST /oauth2/authorize는 SPA 리다이렉트 없이 401")
    void postAuthorizeWithoutCookieIsUnauthorized() throws Exception {
        String clientId = registerPublicClient();

        mockMvc.perform(post("/oauth2/authorize")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", "http://localhost:53421/callback")
                        .param("scope", "mcp:query")
                        .param("state", "s1")
                        .param("code_challenge", codeChallenge())
                        .param("code_challenge_method", "S256")
                        .param("resource", "http://localhost:5173/mcp"))
                .andExpect(status().isUnauthorized());
    }

    // ── 헬퍼 ──

    private ResultActions performAuthorize(String rawQuery, String ticket) throws Exception {
        var request = get(URI.create("/oauth2/authorize?" + rawQuery));
        if (ticket != null) {
            request.cookie(new Cookie(TICKET_COOKIE, ticket));
        }
        return mockMvc.perform(request);
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

    private String registerPublicClient() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("ticket-chain-client-" + UUID.randomUUID())
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

    private AuthorizeParams queryParams(String location) {
        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new AuthorizeParams(params.getFirst("code"), params.getFirst("state"));
    }

    private record AuthorizeParams(String code, String state) {
    }
}
