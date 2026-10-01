package com.history.backend.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.conversation.service.AiEngineQueryClient;
import com.history.backend.conversation.service.AiEngineQueryResult;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.oauth.service.OAuthGrantService;
import com.history.backend.oauth.service.OAuthJwkProvider;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

// MockMvc는 DispatcherServlet만 구동해 /mcp 서블릿에 닿지 않는다 — 실제 포트로 붙어야 등록된 서블릿과
// 보안 필터 체인을 그대로 통과한다. 실제 서버라 테스트 간 DB가 롤백되지 않으므로 사용자·프로젝트·경로는 케이스마다 새로 만든다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("McpServer: /mcp 종단 — 서블릿·보안 체인·도구·프롬프트")
class McpServerEndToEndTest {

    private static final String ISSUER = "http://localhost:5173";
    private static final String MCP_AUDIENCE = ISSUER + "/mcp";
    private static final String PROTOCOL_VERSION = "2025-11-25";

    @LocalServerPort
    private int port;

    @Autowired
    private OAuthJwkProvider jwkProvider;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private OAuthGrantService oAuthGrantService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ServletContext servletContext;

    @MockitoBean
    private AiEngineQueryClient aiEngineQueryClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("토큰 없이 POST하면 401 — SPA 체인이 아니라 MCP 체인의 resource_metadata 챌린지")
    void postWithoutTokenIsRejectedByMcpChain() {
        Reply reply = postMcp(null, initialize());

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("resource_metadata=");
    }

    @Test
    @DisplayName("initialize: 서버 이름·instructions·tools/prompts capability를 돌려준다")
    void initializeReturnsServerInfoInstructionsAndCapabilities() throws Exception {
        Grant grant = grantFor(saveUser());

        Reply reply = postMcp(grant.token(), initialize());

        assertThat(reply.status()).isEqualTo(200);
        JsonNode result = resultOf(reply);
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("whycode");
        assertThat(result.path("instructions").asText()).isNotBlank().contains("whycode");
        assertThat(result.path("capabilities").path("tools").isMissingNode()).isFalse();
        assertThat(result.path("capabilities").path("prompts").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("tools/list는 도구 4개, prompts/list는 connect·why를 돌려준다")
    void toolsAndPromptsAreListed() throws Exception {
        Grant grant = grantFor(saveUser());

        assertThat(namesOf(resultOf(postMcp(grant.token(), rpc("tools/list", Map.of()))).path("tools")))
                .containsExactlyInAnyOrder("list_projects", "bind_project", "ask", "explain_commit");
        assertThat(namesOf(resultOf(postMcp(grant.token(), rpc("prompts/list", Map.of()))).path("prompts")))
                .containsExactlyInAnyOrder("connect", "why");
    }

    @Test
    @DisplayName("list_projects: 사용자의 프로젝트 이름을 담아 돌려준다")
    void listProjectsReturnsOwnProjectName() throws Exception {
        User user = saveUser();
        Project project = saveProject(user, "List Target Project");
        Grant grant = grantFor(user);

        JsonNode result = resultOf(postMcp(grant.token(), toolCall("list_projects", Map.of())));

        assertThat(result.path("isError").asBoolean(false)).isFalse();
        assertThat(textOf(result)).contains(project.getName());
    }

    @Test
    @DisplayName("프로젝트가 1개뿐이어도 연결 전 ask는 자동 연결하지 않고 오류와 bind_project 안내를 돌려준다")
    void askWithoutBindingDoesNotAutoBindSingleProject() throws Exception {
        User user = saveUser();
        Project project = saveProject(user, "Only Project");
        Grant grant = grantFor(user);

        JsonNode result = resultOf(postMcp(grant.token(), toolCall("ask", Map.of(
                "workspace", newWorkspace(), "question", "왜 바뀌었어?"))));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(textOf(result)).contains(project.getName()).contains(project.getId().toString()).contains("bind_project");
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bind_project 뒤 같은 workspace로 ask하면 그 프로젝트 id로 ai-engine을 호출해 답변을 돌려준다")
    void askAfterBindingQueriesBoundProject() throws Exception {
        User user = saveUser();
        Project project = saveProject(user, "Bound Project");
        Grant grant = grantFor(user);
        String workspace = newWorkspace();
        when(aiEngineQueryClient.ask(eq("왜 바뀌었어?"), eq(project.getId()), any(), any(), any(), any()))
                .thenReturn(AiEngineQueryResult.success("연결된 프로젝트의 답변", null));

        JsonNode bound = resultOf(postMcp(grant.token(), toolCall("bind_project", Map.of(
                "workspace", workspace, "project_id", project.getId().toString()))));
        JsonNode answer = resultOf(postMcp(grant.token(), toolCall("ask", Map.of(
                "workspace", workspace, "question", "왜 바뀌었어?"))));

        assertThat(bound.path("isError").asBoolean()).isFalse();
        assertThat(answer.path("isError").asBoolean()).isFalse();
        assertThat(textOf(answer)).isEqualTo("연결된 프로젝트의 답변");
        verify(aiEngineQueryClient).ask(eq("왜 바뀌었어?"), eq(project.getId()), any(), any(), any(), any());
    }

    @Test
    @DisplayName("explain_commit은 해시를 commit evidence focus로 넘긴다")
    void explainCommitPassesCommitAsFocus() throws Exception {
        User user = saveUser();
        Project project = saveProject(user, "Commit Project");
        Grant grant = grantFor(user);
        String workspace = newWorkspace();
        when(aiEngineQueryClient.ask(any(), eq(project.getId()), any(), any(), any(), any()))
                .thenReturn(AiEngineQueryResult.success("커밋 설명", null));
        postMcp(grant.token(), toolCall("bind_project", Map.of(
                "workspace", workspace, "project_id", project.getId().toString())));

        JsonNode result = resultOf(postMcp(grant.token(), toolCall("explain_commit", Map.of(
                "workspace", workspace, "hash", "abc1234"))));

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(textOf(result)).isEqualTo("커밋 설명");
        verify(aiEngineQueryClient).ask(any(), eq(project.getId()), any(), any(), any(),
                eq(List.of(new EvidenceRef("commit", "abc1234"))));
    }

    @Test
    @DisplayName("남의 프로젝트 id로 bind_project하면 거부되고 이후 ask도 연결되지 않은 채로 남는다")
    void bindingOtherUsersProjectIsRejected() throws Exception {
        User userA = saveUser();
        Project projectA = saveProject(userA, "A Project");
        Project projectB = saveProject(saveUser(), "B Project");
        Grant grantA = grantFor(userA);
        String workspace = newWorkspace();

        JsonNode bind = resultOf(postMcp(grantA.token(), toolCall("bind_project", Map.of(
                "workspace", workspace, "project_id", projectB.getId().toString()))));
        JsonNode ask = resultOf(postMcp(grantA.token(), toolCall("ask", Map.of(
                "workspace", workspace, "question", "왜 바뀌었어?"))));

        assertThat(bind.path("isError").asBoolean()).isTrue();
        assertThat(textOf(bind)).contains("찾을 수 없습니다");
        assertThat(ask.path("isError").asBoolean()).isTrue();
        assertThat(textOf(ask)).contains(projectA.getName()).contains("bind_project");
        verify(aiEngineQueryClient, never()).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ai-engine 호출이 예외를 던져도 HTTP 200 + isError 결과이고 예외 메시지는 응답에 없다")
    void aiEngineExceptionBecomesErrorResultWithoutLeak() throws Exception {
        User user = saveUser();
        Project project = saveProject(user, "Failing Project");
        Grant grant = grantFor(user);
        String workspace = newWorkspace();
        when(aiEngineQueryClient.ask(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("secret detail"));
        postMcp(grant.token(), toolCall("bind_project", Map.of(
                "workspace", workspace, "project_id", project.getId().toString())));

        Reply reply = postMcp(grant.token(), toolCall("ask", Map.of(
                "workspace", workspace, "question", "왜 바뀌었어?")));

        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(reply.body());
        assertThat(body.path("error").isMissingNode()).isTrue();
        assertThat(body.path("result").path("isError").asBoolean()).isTrue();
        assertThat(reply.body()).doesNotContain("secret detail");
    }

    @Test
    @DisplayName("연결을 철회하면 같은 토큰으로 tools/list는 401 invalid_token")
    void revokedGrantTokenIsRejected() {
        Grant grant = grantFor(saveUser());
        assertThat(postMcp(grant.token(), rpc("tools/list", Map.of())).status()).isEqualTo(200);

        oAuthGrantService.revoke(grant.userId(), grant.registeredClientId());

        Reply reply = postMcp(grant.token(), rpc("tools/list", Map.of()));
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
    }

    @Test
    @DisplayName("유효 토큰으로 GET /mcp는 405")
    void getMcpIsMethodNotAllowed() {
        Grant grant = grantFor(saveUser());

        Reply reply = send(HttpMethod.GET, grant.token(), null);

        assertThat(reply.status()).isEqualTo(405);
    }

    @Test
    @DisplayName("prompts/get connect: 폴더 연결 안내(bind_project)를 담은 메시지를 돌려준다")
    void connectPromptMentionsBindProject() throws Exception {
        Grant grant = grantFor(saveUser());

        JsonNode result = resultOf(postMcp(grant.token(), rpc("prompts/get", Map.of("name", "connect"))));

        assertThat(result.path("messages").get(0).path("content").path("text").asText()).contains("bind_project");
    }

    // 서블릿 빈이 Boot의 자동 등록으로 다른 경로(빈 이름 경로 등)에도 매핑되면, /mcp만 지키는 보안 체인을
    // 거치지 않고 MCP 서블릿에 닿는 길이 생긴다.
    @Test
    @DisplayName("MCP 서블릿은 /mcp 한 곳에만 매핑된다")
    void mcpServletIsMappedOnlyAtMcp() {
        List<Collection<String>> mappings = servletContext.getServletRegistrations().values().stream()
                .filter(registration -> HttpServletStatelessServerTransport.class.getName().equals(registration.getClassName()))
                .<Collection<String>>map(ServletRegistration::getMappings)
                .toList();

        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0)).containsExactly("/mcp");
    }

    // ── 헬퍼 ──

    // 실제 포트로 Spring RestClient를 직접 만든다 — 4xx도 본문·헤더를 검사하므로 exchange로 받는다
    private Reply postMcp(String token, String body) {
        return send(HttpMethod.POST, token, body);
    }

    private Reply send(HttpMethod method, String token, String body) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method)
                .uri("/mcp")
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, res) -> new Reply(res.getStatusCode().value(), res.getHeaders(), res.bodyTo(String.class)));
    }

    private String initialize() {
        return rpc("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")));
    }

    private String toolCall(String name, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", name, "arguments", arguments));
    }

    private String rpc(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", method);
        message.put("params", params);
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode resultOf(Reply reply) throws Exception {
        assertThat(reply.status()).isEqualTo(200);
        return objectMapper.readTree(reply.body()).path("result");
    }

    private String textOf(JsonNode toolResult) {
        return toolResult.path("content").get(0).path("text").asText();
    }

    private List<String> namesOf(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.path("name").asText()));
        return names;
    }

    private String newWorkspace() {
        return "/home/me/repo-" + UUID.randomUUID();
    }

    private User saveUser() {
        String providerUserId = UUID.randomUUID().toString();
        return userRepository.save(new User("github", providerUserId, providerUserId + "@example.com", "MCP E2E User", null));
    }

    private Project saveProject(User owner, String name) {
        return projectRepository.save(new Project(owner, name, null));
    }

    // 사용자 id를 sub로 직접 서명한 RS256 토큰과, 입구 검증기가 요구하는 같은 값의 연결 행(oauth2_authorization)
    private Grant grantFor(User user) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(user.getId().toString())
                    .audience(MCP_AUDIENCE)
                    .claim("scope", "mcp:query")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                    .build();
            SignedJWT signedJwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(jwkProvider.rsaKey().getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            signedJwt.sign(new RSASSASigner(jwkProvider.rsaKey()));
            String token = signedJwt.serialize();

            RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId("mcp-e2e-client-" + UUID.randomUUID())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/callback")
                    .scope("mcp:query")
                    .build();
            registeredClientRepository.save(client);
            authorizationService.save(OAuth2Authorization.withRegisteredClient(client)
                    .id(UUID.randomUUID().toString())
                    .principalName(user.getId().toString())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .token(new OAuth2AccessToken(
                            OAuth2AccessToken.TokenType.BEARER, token, now, now.plus(Duration.ofHours(1)), Set.of("mcp:query")))
                    .build());
            return new Grant(token, user.getId(), client.getId());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Reply(int status, HttpHeaders headers, String body) {
    }

    private record Grant(String token, UUID userId, String registeredClientId) {
    }
}
