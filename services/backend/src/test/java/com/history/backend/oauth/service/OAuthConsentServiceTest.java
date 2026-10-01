package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.oauth.dto.ConsentPreviewResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

// 검증 실패 케이스는 preview·decide(허용)·decide(거부) 세 경로 모두에서 같은 문구로 거부되는지 확인한다 —
// 거부(deny) 경로가 검증을 건너뛰면 검증되지 않은 redirect_uri로 보내는 오픈 리다이렉트가 된다.
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthConsentService: 동의 화면 preview·decide 검증과 결과 조립")
class OAuthConsentServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final String CLIENT_ID = "consent-client";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CALLBACK = "http%3A%2F%2Flocalhost%3A53421%2Fcallback";
    private static final String RESOURCE = "http%3A%2F%2Flocalhost%3A5173%2Fmcp";

    @Mock
    private UserService userService;

    @Mock
    private RegisteredClientRepository registeredClientRepository;

    @Mock
    private ConsentTicketService consentTicketService;

    private final McpOAuthProperties properties =
            new McpOAuthProperties("http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30), "/oauth/consent");

    // ── preview ──

    @Test
    @DisplayName("preview — 루프백 요청(등록 포트 없음, 요청 포트 53421)이면 필드 전부를 채워 돌려준다")
    void previewReturnsAllFieldsForLoopbackRequest() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));
        String query = query(CALLBACK, "mcp%3Aquery", "s1");

        ConsentPreviewResponse response = service().preview(USER_ID, query);

        assertThat(response.clientName()).isEqualTo("Claude Code");
        assertThat(response.clientUri()).isNull();
        assertThat(response.redirectUri()).isEqualTo("http://localhost:53421/callback");
        assertThat(response.redirectHost()).isEqualTo("localhost");
        assertThat(response.loopback()).isTrue();
        assertThat(response.scopes()).containsExactly("mcp:query");
        verify(userService).getActiveUser(USER_ID);
    }

    @Test
    @DisplayName("preview — https 리다이렉트(완전 일치)는 loopback false, redirectHost는 포트 없는 호스트")
    void previewReturnsHostWithoutPortForHttpsRedirect() {
        stubClient(client("https://app.example.com:8443/oauth/callback", null, "mcp:query"));
        String query = query("https%3A%2F%2Fapp.example.com%3A8443%2Foauth%2Fcallback", "mcp%3Aquery", "s1");

        ConsentPreviewResponse response = service().preview(USER_ID, query);

        assertThat(response.redirectUri()).isEqualTo("https://app.example.com:8443/oauth/callback");
        assertThat(response.redirectHost()).isEqualTo("app.example.com");
        assertThat(response.loopback()).isFalse();
    }

    @Test
    @DisplayName("preview — 127.0.0.1 루프백은 loopback true, redirectHost는 127.0.0.1")
    void previewTreatsIpv4LoopbackAsLoopback() {
        stubClient(client("http://127.0.0.1/callback", null, "mcp:query"));
        String query = query("http%3A%2F%2F127.0.0.1%3A53421%2Fcallback", "mcp%3Aquery", "s1");

        ConsentPreviewResponse response = service().preview(USER_ID, query);

        assertThat(response.redirectHost()).isEqualTo("127.0.0.1");
        assertThat(response.loopback()).isTrue();
    }

    @Test
    @DisplayName("preview — [::1] 루프백도 loopback true")
    void previewTreatsIpv6LoopbackAsLoopback() {
        stubClient(client("http://[::1]/callback", null, "mcp:query"));
        String query = query("http%3A%2F%2F%5B%3A%3A1%5D%3A53421%2Fcallback", "mcp%3Aquery", "s1");

        ConsentPreviewResponse response = service().preview(USER_ID, query);

        assertThat(response.redirectUri()).isEqualTo("http://[::1]:53421/callback");
        assertThat(response.loopback()).isTrue();
    }

    @Test
    @DisplayName("preview — 클라이언트에 client_uri 설정이 있으면 clientUri로 돌려준다")
    void previewReturnsClientUriWhenConfigured() {
        stubClient(client("http://localhost/callback", "https://claude.ai", "mcp:query"));

        ConsentPreviewResponse response = service().preview(USER_ID, query(CALLBACK, "mcp%3Aquery", "s1"));

        assertThat(response.clientUri()).isEqualTo("https://claude.ai");
    }

    @Test
    @DisplayName("preview — scope가 없으면 [mcp:query]로 채운다")
    void previewDefaultsScopeWhenMissing() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        ConsentPreviewResponse response = service().preview(USER_ID, query(CALLBACK, null, "s1"));

        assertThat(response.scopes()).containsExactly("mcp:query");
    }

    @Test
    @DisplayName("preview — 공백뿐인 scope도 [mcp:query]로 채운다")
    void previewDefaultsScopeWhenBlank() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        ConsentPreviewResponse response = service().preview(USER_ID, query(CALLBACK, "+", "s1"));

        assertThat(response.scopes()).containsExactly("mcp:query");
    }

    @Test
    @DisplayName("preview — 퍼센트 인코딩된 scope(mcp%3Aquery)를 디코딩해 돌려준다")
    void previewDecodesScope() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        ConsentPreviewResponse response = service().preview(USER_ID, query(CALLBACK, "mcp%3Aquery", "s1"));

        assertThat(response.scopes()).containsExactly("mcp:query");
    }

    @Test
    @DisplayName("preview — 공백으로 구분된 scope는 요청 순서대로 나눠 돌려준다")
    void previewSplitsScopeBySpace() {
        stubClient(client("http://localhost/callback", null, "mcp:query", "mcp:extra"));

        ConsentPreviewResponse response = service().preview(USER_ID, query(CALLBACK, "mcp%3Aquery+mcp%3Aextra", "s1"));

        assertThat(response.scopes()).containsExactly("mcp:query", "mcp:extra");
    }

    @Test
    @DisplayName("preview — 등록되지 않은 scope가 섞이면 거부")
    void previewRejectsUnregisteredScope() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery+admin", "s1"), OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    // ── 검증 실패 (preview·decide 허용·decide 거부 공통) ──

    @Test
    @DisplayName("쿼리가 null이면 거부")
    void rejectsNullQuery() {
        assertRejectedEverywhere(null, OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("쿼리가 빈 문자열이면 거부")
    void rejectsEmptyQuery() {
        assertRejectedEverywhere("", OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("깨진 퍼센트 인코딩(%zz)이면 거부")
    void rejectsMalformedPercentEncoding() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "bad%zz"), OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("client_id가 두 번 오면 거부")
    void rejectsDuplicateClientId() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1") + "&client_id=other",
                OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("redirect_uri가 두 번 오면 거부")
    void rejectsDuplicateRedirectUri() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1") + "&redirect_uri=https%3A%2F%2Fevil.example%2Fcb",
                OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("response_type이 code가 아니면 거부")
    void rejectsNonCodeResponseType() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1").replace("response_type=code", "response_type=token"),
                OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("client_id가 없으면 거부(등록되지 않은 앱 문구)")
    void rejectsMissingClientId() {
        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1").replace("client_id=" + CLIENT_ID + "&", ""),
                OAuthConsentService.MESSAGE_UNKNOWN_CLIENT);
    }

    @Test
    @DisplayName("등록되지 않은 client_id면 거부(등록되지 않은 앱 문구)")
    void rejectsUnknownClient() {
        lenient().when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(null);

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1"), OAuthConsentService.MESSAGE_UNKNOWN_CLIENT);
    }

    @Test
    @DisplayName("redirect_uri가 없으면 거부(등록되지 않은 앱 문구)")
    void rejectsMissingRedirectUri() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1").replace("redirect_uri=" + CALLBACK + "&", ""),
                OAuthConsentService.MESSAGE_UNKNOWN_CLIENT);
    }

    @Test
    @DisplayName("redirect_uri 경로가 등록값과 다르면 거부(등록되지 않은 앱 문구)")
    void rejectsRedirectUriWithDifferentPath() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query("http%3A%2F%2Flocalhost%3A53421%2Fother", "mcp%3Aquery", "s1"),
                OAuthConsentService.MESSAGE_UNKNOWN_CLIENT);
    }

    @Test
    @DisplayName("code_challenge가 없으면 거부")
    void rejectsMissingCodeChallenge() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1").replace("&code_challenge=" + CHALLENGE, ""),
                OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("code_challenge_method가 S256이 아니면 거부")
    void rejectsNonS256ChallengeMethod() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1").replace("code_challenge_method=S256", "code_challenge_method=plain"),
                OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("resource가 이 서버의 /mcp와 다르면 거부")
    void rejectsMismatchedResource() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertRejectedEverywhere(query(CALLBACK, "mcp%3Aquery", "s1").replace(RESOURCE, "https%3A%2F%2Fevil.example%2Fmcp"),
                OAuthConsentService.MESSAGE_INVALID_REQUEST);
    }

    @Test
    @DisplayName("탈퇴한 사용자는 getActiveUser의 예외가 그대로 전파되고 클라이언트 조회·티켓 발급이 없다")
    void withdrawnUserPropagatesExceptionWithoutSideEffects() {
        when(userService.getActiveUser(USER_ID)).thenThrow(new NotFoundException("User not found."));
        String query = query(CALLBACK, "mcp%3Aquery", "s1");

        assertThatThrownBy(() -> service().preview(USER_ID, query)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service().decide(USER_ID, query, true)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service().decide(USER_ID, query, false)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(registeredClientRepository, consentTicketService);
    }

    // ── decide ──

    @Test
    @DisplayName("decide 허용 — redirectTo는 /oauth2/authorize? + 원문 쿼리, 티켓은 원문 쿼리로 1회 발급")
    void decideApprovedKeepsRawQueryAndIssuesTicket() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));
        String query = query(CALLBACK, "mcp%3Aquery", "a%20b+c");
        when(consentTicketService.issue(USER_ID, query)).thenReturn("ticket-1");

        ConsentDecision decision = service().decide(USER_ID, query, true);

        assertThat(decision.redirectTo()).isEqualTo("/oauth2/authorize?" + query);
        assertThat(decision.ticket()).isEqualTo("ticket-1");
        verify(consentTicketService).issue(USER_ID, query);
    }

    @Test
    @DisplayName("decide 거부 — 앱 redirect_uri에 error=access_denied와 state를 붙이고 티켓은 발급하지 않는다")
    void decideDeniedRedirectsWithAccessDenied() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        ConsentDecision decision = service().decide(USER_ID, query(CALLBACK, "mcp%3Aquery", "s1"), false);

        assertThat(decision.redirectTo()).isEqualTo("http://localhost:53421/callback?error=access_denied&state=s1");
        assertThat(decision.ticket()).isNull();
        verifyNoInteractions(consentTicketService);
    }

    @Test
    @DisplayName("decide 거부 — state가 없는 요청은 state 파라미터 없이 error만 붙인다")
    void decideDeniedOmitsStateWhenAbsent() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        ConsentDecision decision = service().decide(USER_ID, query(CALLBACK, "mcp%3Aquery", null), false);

        assertThat(decision.redirectTo()).startsWith("http://localhost:53421/callback?");
        assertThat(queryParamsOf(decision.redirectTo())).containsOnlyKeys("error").containsEntry("error", "access_denied");
        assertThat(decision.ticket()).isNull();
    }

    @Test
    @DisplayName("decide 거부 — 특수문자 state는 돌려받는 주소를 파싱·디코딩하면 원래 값과 같다")
    void decideDeniedEncodesSpecialCharactersInState() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));
        // 디코딩하면 'a b&c=d/é%+#'
        String query = query(CALLBACK, "mcp%3Aquery", "a%20b%26c%3Dd%2F%C3%A9%25%2B%23");

        ConsentDecision decision = service().decide(USER_ID, query, false);

        assertThat(decision.redirectTo()).doesNotContain("#");
        assertThat(queryParamsOf(decision.redirectTo()))
                .containsEntry("error", "access_denied")
                .containsEntry("state", "a b&c=d/é%+#");
    }

    @Test
    @DisplayName("쿼리의 +는 공백으로 디코딩된다 — state=a+b 거부 시 돌려받는 state는 'a b'")
    void plusInQueryDecodesToSpace() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        ConsentDecision decision = service().decide(USER_ID, query(CALLBACK, "mcp%3Aquery", "a+b"), false);

        assertThat(queryParamsOf(decision.redirectTo())).containsEntry("state", "a b");
    }

    @Test
    @DisplayName("decide 허용인데 검증 실패 → 예외, 티켓 발급 없음")
    void decideApprovedRejectsInvalidRequestWithoutIssuingTicket() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));
        String query = query(CALLBACK, "mcp%3Aquery", "s1").replace("code_challenge_method=S256", "code_challenge_method=plain");

        assertThatThrownBy(() -> service().decide(USER_ID, query, true)).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(consentTicketService);
    }

    @Test
    @DisplayName("decide 거부인데 모르는 client → 예외(검증되지 않은 주소로 보내지 않는다)")
    void decideDeniedRejectsUnknownClient() {
        lenient().when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service().decide(USER_ID, query(CALLBACK, "mcp%3Aquery", "s1"), false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(OAuthConsentService.MESSAGE_UNKNOWN_CLIENT);
    }

    @Test
    @DisplayName("decide 거부인데 redirect_uri가 등록값과 다르면 → 예외(오픈 리다이렉트 차단)")
    void decideDeniedRejectsMismatchedRedirectUri() {
        stubClient(client("http://localhost/callback", null, "mcp:query"));

        assertThatThrownBy(() -> service().decide(USER_ID,
                query("https%3A%2F%2Fevil.example%2Fcb", "mcp%3Aquery", "s1"), false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(OAuthConsentService.MESSAGE_UNKNOWN_CLIENT);
    }

    // ── 헬퍼 ──

    private OAuthConsentService service() {
        return new OAuthConsentService(userService, registeredClientRepository, consentTicketService, properties);
    }

    // 세 경로(preview·decide 허용·decide 거부)가 모두 같은 문구의 BadRequestException으로 거부하고 티켓을 발급하지 않음을 확인
    private void assertRejectedEverywhere(String rawQuery, String message) {
        OAuthConsentService service = service();

        assertThatThrownBy(() -> service.preview(USER_ID, rawQuery))
                .isInstanceOf(BadRequestException.class).hasMessage(message);
        assertThatThrownBy(() -> service.decide(USER_ID, rawQuery, true))
                .isInstanceOf(BadRequestException.class).hasMessage(message);
        assertThatThrownBy(() -> service.decide(USER_ID, rawQuery, false))
                .isInstanceOf(BadRequestException.class).hasMessage(message);
        verifyNoInteractions(consentTicketService);
    }

    // 검증 순서에 따라 조회 전에 거부되는 케이스도 있으므로 lenient
    private void stubClient(RegisteredClient client) {
        lenient().when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(client);
    }

    private RegisteredClient client(String redirectUri, String clientUri, String... scopes) {
        ClientSettings.Builder settings = ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false);
        if (clientUri != null) {
            settings.setting(McpRegisteredClientPolicy.CLIENT_URI_SETTING, clientUri);
        }
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientName("Claude Code")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scopes(set -> set.addAll(List.of(scopes)))
                .clientSettings(settings.build())
                .build();
    }

    // scope·state가 null이면 그 파라미터를 뺀다. 값은 이미 인코딩된 상태로 넘긴다.
    private String query(String encodedRedirectUri, String encodedScope, String encodedState) {
        StringBuilder query = new StringBuilder("response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=" + encodedRedirectUri);
        if (encodedScope != null) {
            query.append("&scope=").append(encodedScope);
        }
        if (encodedState != null) {
            query.append("&state=").append(encodedState);
        }
        return query + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256&resource=" + RESOURCE;
    }

    // 돌려받은 앱 주소의 쿼리를 폼 디코딩으로 파싱 — 앱(서블릿)이 읽는 방식과 같다
    private Map<String, String> queryParamsOf(String url) {
        String rawQuery = url.substring(url.indexOf('?') + 1);
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
