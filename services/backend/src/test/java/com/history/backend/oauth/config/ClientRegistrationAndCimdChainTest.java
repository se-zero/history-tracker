package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.oauth.service.CimdClientMetadata;
import com.history.backend.oauth.service.CimdDocumentFetcher;
import com.history.backend.oauth.service.CimdDocumentInvalidException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

// B2(CIMD 해석 + DCR 폴백) 종단 — 열린 등록(/oauth2/register)과 CIMD client_id 인식이
// /oauth2/authorize 진입점까지 이어지는지 확인한다. 로그인 세션이 없으므로 인가 결정 자체는
// B1 그대로 401(HttpStatusEntryPoint)이고, 여기서는 그 앞 단계(클라이언트 조회·그림자 upsert)만 본다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DCR 등록 엔드포인트·CIMD client_id 인식 체인")
class ClientRegistrationAndCimdChainTest {

    private static final String ISSUER = "http://localhost:5173";
    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CimdDocumentFetcher fetcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("POST /oauth2/register — 공개 클라이언트 등록 성공")
    void registerEndpointCreatesPublicClient() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Test MCP Client",
                                  "redirect_uris": ["http://localhost/callback"],
                                  "token_endpoint_auth_method": "none",
                                  "grant_types": ["authorization_code", "refresh_token"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").exists())
                .andExpect(jsonPath("$.client_secret").doesNotExist())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
                .andExpect(jsonPath("$.redirect_uris[0]").value("http://localhost/callback"))
                .andExpect(jsonPath("$.scope").value("mcp:query"))
                .andExpect(jsonPath("$.grant_types", Matchers.hasItems("authorization_code", "refresh_token")));
    }

    @Test
    @DisplayName("POST /oauth2/register — client_secret_post 인증 방식 요청은 400 invalid_client_metadata")
    void registerEndpointRejectsNonNoneAuthMethod() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Test MCP Client",
                                  "redirect_uris": ["http://localhost/callback"],
                                  "token_endpoint_auth_method": "client_secret_post"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @Test
    @DisplayName("POST /oauth2/register — 등록 불가능한 redirect_uri는 400 invalid_redirect_uri")
    void registerEndpointRejectsNonRegistrableRedirectUri() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Test MCP Client",
                                  "redirect_uris": ["http://example.com/cb"],
                                  "token_endpoint_auth_method": "none"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    }

    @Test
    @DisplayName("POST /oauth2/register — 호스트 없는 redirect_uri(http:///cb)도 500이 아니라 400 invalid_redirect_uri")
    void registerEndpointRejectsHostlessRedirectUriWithoutServerError() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Test MCP Client",
                                  "redirect_uris": ["http:///cb"],
                                  "token_endpoint_auth_method": "none"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    }

    @Test
    @DisplayName("인가 서버 메타데이터 — registration_endpoint 노출")
    void authorizationServerMetadataExposesRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint").value(ISSUER + "/oauth2/register"));
    }

    @Test
    @DisplayName("CIMD client_id로 /oauth2/authorize — 클라이언트를 인식(401)하고 그림자 행을 upsert")
    void authorizeRequestWithCimdClientIdRecognizesClientAndShadowsRow() throws Exception {
        String clientIdUrl = "https://cimd.example/client";
        when(fetcher.fetch(clientIdUrl)).thenReturn(new CimdClientMetadata(
                clientIdUrl, "CIMD Test", "https://cimd.example", Set.of("http://localhost/callback")));

        performAuthorize(clientIdUrl, "http://localhost:4321/callback")
                .andExpect(status().isUnauthorized());

        assertThat(registeredClientCount(clientIdUrl)).isEqualTo(1);

        performAuthorize(clientIdUrl, "http://localhost:4321/callback")
                .andExpect(status().isUnauthorized());

        assertThat(registeredClientCount(clientIdUrl)).isEqualTo(1);
    }

    @Test
    @DisplayName("CIMD 문서가 영구 무효면 /oauth2/authorize는 400(모르는 클라이언트), 그림자 행 없음")
    void authorizeRequestWithInvalidCimdDocumentIsUnknownClient() throws Exception {
        String clientIdUrl = "https://invalid.example/client";
        when(fetcher.fetch(clientIdUrl)).thenThrow(new CimdDocumentInvalidException("bad"));

        performAuthorize(clientIdUrl, "http://localhost:4321/callback")
                .andExpect(status().isBadRequest());

        assertThat(registeredClientCount(clientIdUrl)).isEqualTo(0);
    }

    @Test
    @DisplayName("DCR로 등록한 client_id로 /oauth2/authorize — 실제 저장된 클라이언트로 401")
    void authorizeRequestWithDcrRegisteredClientIdRecognizesClient() throws Exception {
        MvcResult registerResult = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Test MCP Client",
                                  "redirect_uris": ["http://localhost/callback"],
                                  "token_endpoint_auth_method": "none"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        String clientId = body.get("client_id").asText();

        performAuthorize(clientId, "http://localhost:4321/callback")
                .andExpect(status().isUnauthorized());
    }

    // ── 헬퍼 ──

    private ResultActions performAuthorize(String clientId, String redirectUri) throws Exception {
        return mockMvc.perform(get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("code_challenge", CODE_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", "s1"));
    }

    private Integer registeredClientCount(String clientId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from oauth2_registered_client where client_id = ?", Integer.class, clientId);
    }
}
