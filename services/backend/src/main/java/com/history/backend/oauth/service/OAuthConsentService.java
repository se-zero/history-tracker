package com.history.backend.oauth.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.oauth.dto.ConsentPreviewResponse;
import com.history.backend.oauth.security.McpAuthorizationRequestValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

// SPA 동의 화면의 preview·decide. 두 경로 모두 /oauth2/authorize가 나중에 하는 검증과 같은 기준으로
// 요청을 먼저 걸러, 잘못된 요청에 동의 화면이 뜨거나 티켓이 발급되는 일을 막는다.
@Service
public class OAuthConsentService {

    public static final String MESSAGE_UNKNOWN_CLIENT =
            "등록되지 않은 앱이거나 돌아갈 주소가 일치하지 않습니다. 앱에서 연결을 다시 시작해 주세요.";
    public static final String MESSAGE_INVALID_REQUEST =
            "연결 요청이 올바르지 않습니다. 앱에서 연결을 다시 시작해 주세요.";

    private static final Set<String> SINGLE_VALUED = Set.of(
            "response_type", "client_id", "redirect_uri", "scope", "state",
            "code_challenge", "code_challenge_method", "resource");

    private final UserService userService;
    private final RegisteredClientRepository registeredClientRepository;
    private final ConsentTicketService consentTicketService;
    private final McpOAuthProperties properties;

    public OAuthConsentService(
            UserService userService,
            RegisteredClientRepository registeredClientRepository,
            ConsentTicketService consentTicketService,
            McpOAuthProperties properties
    ) {
        this.userService = userService;
        this.registeredClientRepository = registeredClientRepository;
        this.consentTicketService = consentTicketService;
        this.properties = properties;
    }

    public ConsentPreviewResponse preview(UUID userId, String rawQuery) {
        ValidatedRequest request = validate(userId, rawQuery);
        String host = URI.create(request.redirectUri()).getHost();
        return new ConsentPreviewResponse(
                request.client().getClientName(),
                McpRegisteredClientPolicy.clientUriOf(request.client()),
                request.redirectUri(),
                host,
                McpAuthorizationRequestValidator.isLoopbackHost(host),
                request.scopes());
    }

    public ConsentDecision decide(UUID userId, String rawQuery, boolean approved) {
        ValidatedRequest request = validate(userId, rawQuery);
        if (approved) {
            // 프론트가 이 경로로 이동하고, 티켓 필터가 그 요청의 쿼리 원문 해시를 티켓과 대조한다.
            // 파싱한 값으로 다시 조립(재인코딩)하면 한 글자라도 달라져 티켓이 거부되므로 원문을 그대로 쓴다.
            return new ConsentDecision("/oauth2/authorize?" + rawQuery, consentTicketService.issue(userId, rawQuery));
        }
        // 거부도 허용과 똑같이 검증한 뒤에만 redirect_uri로 보낸다 — 검증 없이 보내면 공격자가 고른
        // 주소로 사용자를 돌려보내는 오픈 리다이렉트가 된다.
        StringBuilder denied = new StringBuilder(request.redirectUri())
                .append(request.redirectUri().contains("?") ? "&" : "?")
                .append("error=access_denied");
        String state = request.params().get("state");
        if (state != null) {
            // 폼 인코딩(공백→+)이 아니라 엄격한 퍼센트 인코딩을 쓴다 — 앱이 +를 공백으로 읽든 아니든 같은 값이 나온다.
            denied.append("&state=").append(UriUtils.encode(state, StandardCharsets.UTF_8));
        }
        return new ConsentDecision(denied.toString(), null);
    }

    private ValidatedRequest validate(UUID userId, String rawQuery) {
        userService.getActiveUser(userId);
        Map<String, String> params = parseQuery(rawQuery);

        if (!"code".equals(params.get("response_type"))) {
            throw new BadRequestException(MESSAGE_INVALID_REQUEST);
        }

        String clientId = params.get("client_id");
        RegisteredClient client = (clientId == null || clientId.isEmpty())
                ? null : registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw new BadRequestException(MESSAGE_UNKNOWN_CLIENT);
        }

        String redirectUri = params.get("redirect_uri");
        if (redirectUri == null || redirectUri.isBlank()
                || !McpAuthorizationRequestValidator.redirectUriMatches(redirectUri, client.getRedirectUris())) {
            throw new BadRequestException(MESSAGE_UNKNOWN_CLIENT);
        }

        String challenge = params.get("code_challenge");
        if (challenge == null || challenge.isEmpty() || !"S256".equals(params.get("code_challenge_method"))) {
            throw new BadRequestException(MESSAGE_INVALID_REQUEST);
        }

        List<String> scopes = resolveScopes(params.get("scope"), client);

        String resource = params.get("resource");
        if (resource != null && !resource.equals(properties.resourceUrl())) {
            throw new BadRequestException(MESSAGE_INVALID_REQUEST);
        }
        return new ValidatedRequest(client, redirectUri, scopes, params);
    }

    private List<String> resolveScopes(String scope, RegisteredClient client) {
        if (scope == null || scope.isBlank()) {
            return List.of(McpRegisteredClientPolicy.SCOPE);
        }
        List<String> scopes = Arrays.asList(scope.trim().split("\\s+"));
        if (!client.getScopes().containsAll(scopes)) {
            throw new BadRequestException(MESSAGE_INVALID_REQUEST);
        }
        return scopes;
    }

    // 서블릿이 읽는 방식과 같은 폼 디코딩(+는 공백). 같은 파라미터가 두 번 오면 해석이 갈릴 수 있어
    // 핵심 파라미터의 중복은 애초에 거부한다.
    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            throw new BadRequestException(MESSAGE_INVALID_REQUEST);
        }
        Map<String, String> params = new HashMap<>();
        try {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (params.put(name, value) != null && SINGLE_VALUED.contains(name)) {
                    throw new BadRequestException(MESSAGE_INVALID_REQUEST);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(MESSAGE_INVALID_REQUEST);
        }
        return params;
    }

    private record ValidatedRequest(
            RegisteredClient client, String redirectUri, List<String> scopes, Map<String, String> params) {
    }
}
