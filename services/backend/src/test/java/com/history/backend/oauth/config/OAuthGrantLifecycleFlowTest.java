package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.auth.service.UserService;
import com.history.backend.oauth.service.ConsentTicketService;
import com.history.backend.oauth.service.OAuthGrantService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

// D(계정 삭제 연동·만료 행 정리) 종단 — 실제 연결을 만든 뒤 실제 UserService.deactivateUser·
// OAuthGrantService.purgeExpired와 SQL(H2)이 연결을 지우고 refresh 교환을 막는지 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("연결 수명주기 종단: 탈퇴 → 연결 삭제, 만료 → 행 정리")
class OAuthGrantLifecycleFlowTest {

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

    @Autowired
    private UserService userService;

    @Autowired
    private OAuthGrantService oAuthGrantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 다른 테스트 클래스가 같은 H2에 남긴 행이 purgeExpired 반환 건수를 흔들지 않도록 비운다
    @BeforeEach
    void clearAuthorizations() {
        jdbcTemplate.update("DELETE FROM oauth2_authorization");
    }

    @Test
    @DisplayName("탈퇴하면 그 사용자의 연결 행이 0개가 되고 refresh 교환은 400 invalid_grant, 다른 사용자의 연결은 그대로")
    void deactivateUserRemovesOwnGrantsAndKeepsOthers() throws Exception {
        UUID leaver = saveActiveUser();
        UUID stayer = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        String leaverRefresh = connect(leaver, client);
        String stayerRefresh = connect(stayer, client);

        userService.deactivateUser(leaver);

        assertThat(authorizationRowCount(leaver)).isZero();
        assertThat(authorizationRowCount(stayer)).isEqualTo(1);
        mockMvc.perform(refreshRequest(client, leaverRefresh))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(refreshRequest(client, stayerRefresh))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("만료된 연결 한쪽만 purgeExpired로 지워지고(반환 1) 나머지는 refresh 교환이 200")
    void purgeExpiredDeletesOnlyExpiredGrant() throws Exception {
        UUID expiredUser = saveActiveUser();
        UUID liveUser = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        String expiredRefresh = connect(expiredUser, client);
        String liveRefresh = connect(liveUser, client);
        expireAllColumns(expiredUser);

        int purged = oAuthGrantService.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(authorizationRowCount(expiredUser)).isZero();
        assertThat(authorizationRowCount(liveUser)).isEqualTo(1);
        mockMvc.perform(refreshRequest(client, expiredRefresh))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(refreshRequest(client, liveRefresh))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("코드만 발급되고 교환 안 된 행은 코드 만료 시각이 지나면 purgeExpired가 지운다")
    void purgeExpiredDeletesCodeOnlyRowWithExpiredCode() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        authorizeForCode(userId, client);
        jdbcTemplate.update("UPDATE oauth2_authorization SET authorization_code_expires_at = ? WHERE principal_name = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), userId.toString());

        int purged = oAuthGrantService.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(authorizationRowCount(userId)).isZero();
    }

    @Test
    @DisplayName("코드만 발급됐고 아직 유효한 행은 purgeExpired가 지우지 않는다")
    void purgeExpiredKeepsCodeOnlyRowWithValidCode() throws Exception {
        UUID userId = saveActiveUser();
        RegisteredClient client = registerPublicClient();
        authorizeForCode(userId, client);

        int purged = oAuthGrantService.purgeExpired();

        assertThat(purged).isZero();
        assertThat(authorizationRowCount(userId)).isEqualTo(1);
    }

    // ── 헬퍼 ──

    private void expireAllColumns(UUID userId) {
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofDays(1)));
        jdbcTemplate.update("""
                UPDATE oauth2_authorization
                   SET authorization_code_expires_at = ?, access_token_expires_at = ?, refresh_token_expires_at = ?
                 WHERE principal_name = ?
                """, past, past, past, userId.toString());
    }

    private int authorizationRowCount(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?", Integer.class, userId.toString());
        return count == null ? 0 : count;
    }

    private UUID saveActiveUser() {
        String providerUserId = UUID.randomUUID().toString();
        User user = userRepository.save(
                new User("github", providerUserId, providerUserId + "@example.com", "Lifecycle Flow User", null));
        return user.getId();
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

    private RegisteredClient registerPublicClient() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("lifecycle-flow-client-" + UUID.randomUUID())
                .clientName("Lifecycle Flow Client")
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
