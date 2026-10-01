package com.history.backend.oauth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.oauth.dto.ConsentPreviewResponse;
import com.history.backend.oauth.service.ConsentDecision;
import com.history.backend.oauth.service.OAuthConsentService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OAuthConsentController: 동의 화면 preview·decide")
class OAuthConsentControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    // 퍼센트 인코딩·+ 가 섞인 원본 쿼리 — 컨트롤러가 재인코딩하지 않고 원문을 넘겨야 한다
    private static final String RAW_QUERY = "response_type=code&client_id=c1"
            + "&redirect_uri=http%3A%2F%2Flocalhost%3A53421%2Fcallback&scope=mcp%3Aquery&state=a+b%2Fc";
    private static final String TICKET_COOKIE = "wc_oauth_ticket";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OAuthConsentService oAuthConsentService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpAuthentication() {
        // 모든 요청을 USER_ID로 인증 통과시킨다
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("preview — 서비스에 원본 쿼리를 그대로 넘기고 6개 필드를 JSON으로 반환")
    void previewPassesRawQueryAndReturnsFields() throws Exception {
        when(oAuthConsentService.preview(USER_ID, RAW_QUERY)).thenReturn(new ConsentPreviewResponse(
                "Claude Code", "https://claude.ai", "http://localhost:53421/callback", "localhost", true, List.of("mcp:query")));

        mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + RAW_QUERY))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientName").value("Claude Code"))
                .andExpect(jsonPath("$.clientUri").value("https://claude.ai"))
                .andExpect(jsonPath("$.redirectUri").value("http://localhost:53421/callback"))
                .andExpect(jsonPath("$.redirectHost").value("localhost"))
                .andExpect(jsonPath("$.loopback").value(true))
                .andExpect(jsonPath("$.scopes").isArray())
                .andExpect(jsonPath("$.scopes[0]").value("mcp:query"));

        verify(oAuthConsentService).preview(USER_ID, RAW_QUERY);
    }

    @Test
    @DisplayName("preview — clientUri가 null이면 키는 있고 값은 JSON null")
    void previewSerializesNullClientUriAsJsonNull() throws Exception {
        when(oAuthConsentService.preview(USER_ID, RAW_QUERY)).thenReturn(new ConsentPreviewResponse(
                "Claude Code", null, "http://localhost:53421/callback", "localhost", true, List.of("mcp:query")));

        MvcResult result = mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + RAW_QUERY))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("clientUri")).isTrue();
        assertThat(body.get("clientUri").isNull()).isTrue();
    }

    @Test
    @DisplayName("preview — 서비스가 BadRequestException을 던지면 400과 그 문구")
    void previewMapsBadRequestToMessage() throws Exception {
        when(oAuthConsentService.preview(USER_ID, RAW_QUERY))
                .thenThrow(new BadRequestException(OAuthConsentService.MESSAGE_UNKNOWN_CLIENT));

        mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + RAW_QUERY))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(OAuthConsentService.MESSAGE_UNKNOWN_CLIENT));
    }

    @Test
    @DisplayName("preview — 토큰이 없으면 401, 서비스 미호출")
    void previewRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?" + RAW_QUERY)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(oAuthConsentService);
    }

    @Test
    @DisplayName("decide — 토큰이 없으면 401, 서비스 미호출")
    void decideRejectsMissingAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/oauth/consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"a=b\",\"approved\":true}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(oAuthConsentService);
    }

    @Test
    @DisplayName("decide 허용 — JWT 사용자·원문 query로 서비스 호출, 본문은 redirectTo뿐이고 티켓은 쿠키로만")
    void decideApprovedSetsTicketCookieAndOmitsTicketFromBody() throws Exception {
        when(oAuthConsentService.decide(USER_ID, RAW_QUERY, true))
                .thenReturn(new ConsentDecision("/oauth2/authorize?" + RAW_QUERY, "ticket-abc"));

        MvcResult result = mockMvc.perform(post("/api/v1/oauth/consent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decideBody(RAW_QUERY, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectTo").value("/oauth2/authorize?" + RAW_QUERY))
                .andExpect(jsonPath("$.ticket").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("ticket-abc");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie)
                        .startsWith(TICKET_COOKIE + "=ticket-abc;")
                        .contains("HttpOnly")
                        .contains("SameSite=Lax")
                        .contains("Path=/oauth2/authorize")
                        .contains("Max-Age=60")
                        .doesNotContain("Secure"));
        verify(oAuthConsentService).decide(USER_ID, RAW_QUERY, true);
    }

    @Test
    @DisplayName("decide 허용 — 보안 연결(https) 요청이면 쿠키에 Secure")
    void decideApprovedMarksCookieSecureOnSecureRequest() throws Exception {
        when(oAuthConsentService.decide(USER_ID, RAW_QUERY, true))
                .thenReturn(new ConsentDecision("/oauth2/authorize?" + RAW_QUERY, "ticket-abc"));

        MvcResult result = mockMvc.perform(post("/api/v1/oauth/consent")
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decideBody(RAW_QUERY, true)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie).startsWith(TICKET_COOKIE + "=ticket-abc;").contains("Secure"));
    }

    @Test
    @DisplayName("decide 거부 — 티켓이 없으면 Set-Cookie 없이 redirectTo만")
    void decideDeniedSetsNoCookie() throws Exception {
        String deniedUrl = "http://localhost:53421/callback?error=access_denied&state=s1";
        when(oAuthConsentService.decide(USER_ID, RAW_QUERY, false)).thenReturn(new ConsentDecision(deniedUrl, null));

        MvcResult result = mockMvc.perform(post("/api/v1/oauth/consent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decideBody(RAW_QUERY, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectTo").value(deniedUrl))
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    @DisplayName("decide — 서비스가 BadRequestException을 던지면 400과 그 문구, 쿠키 없음")
    void decideMapsBadRequestToMessage() throws Exception {
        when(oAuthConsentService.decide(USER_ID, RAW_QUERY, true))
                .thenThrow(new BadRequestException(OAuthConsentService.MESSAGE_INVALID_REQUEST));

        MvcResult result = mockMvc.perform(post("/api/v1/oauth/consent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decideBody(RAW_QUERY, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(OAuthConsentService.MESSAGE_INVALID_REQUEST))
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    @DisplayName("decide — approved가 없으면 400, 서비스 미호출")
    void decideRejectsMissingApproved() throws Exception {
        mockMvc.perform(post("/api/v1/oauth/consent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"a=b\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(oAuthConsentService);
    }

    @Test
    @DisplayName("decide — query가 없으면 400, 서비스 미호출")
    void decideRejectsMissingQuery() throws Exception {
        mockMvc.perform(post("/api/v1/oauth/consent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(oAuthConsentService);
    }

    // query를 JSON 문자열로 안전하게 감싼다(이스케이프는 Jackson이)
    private String decideBody(String query, boolean approved) throws Exception {
        return objectMapper.writeValueAsString(Map.of("query", query, "approved", approved));
    }
}
