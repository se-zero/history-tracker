package com.history.backend.oauth.security;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

// MCP 인가 요청 검증: redirect_uri → resource(RFC 8707, SAS 7.0.4 미지원) → scope 순으로 체이닝한다.
// redirectUriMatches를 static으로 뺀 이유는 B3의 동의 미리보기가 같은 판정을 재사용하기 위함이다.
public class McpAuthorizationRequestValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final String resourceUrl;

    public McpAuthorizationRequestValidator(String resourceUrl) {
        this.resourceUrl = resourceUrl;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        RegisteredClient registeredClient = context.getRegisteredClient();

        validateRedirectUri(authentication, registeredClient);
        validateResource(authentication);
        OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
    }

    private void validateRedirectUri(
            OAuth2AuthorizationCodeRequestAuthenticationToken authentication, RegisteredClient registeredClient) {
        String requestedRedirectUri = authentication.getRedirectUri();
        boolean valid = (requestedRedirectUri == null || requestedRedirectUri.isBlank())
                ? registeredClient.getRedirectUris().size() == 1
                : redirectUriMatches(requestedRedirectUri, registeredClient.getRedirectUris());
        if (!valid) {
            // redirect_uri 자체를 신뢰할 수 없으므로 클라이언트로 리다이렉트하지 않는다(두 번째 인자 null).
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_REQUEST,
                            "OAuth 2.0 Parameter: redirect_uri",
                            "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"),
                    null);
        }
    }

    private void validateResource(OAuth2AuthorizationCodeRequestAuthenticationToken authentication) {
        Object resource = authentication.getAdditionalParameters().get(OAuth2ParameterNames.RESOURCE);
        if (resource != null && !resourceUrl.equals(resource)) {
            // redirect_uri는 이미 검증됐으니 이번엔 클라이언트로 오류 리다이렉트한다(두 번째 인자에 원본 토큰).
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(
                            "invalid_target",
                            "OAuth 2.0 Parameter: resource",
                            "https://datatracker.ietf.org/doc/html/rfc8707#section-2"),
                    authentication);
        }
    }

    // 루프백(localhost/127.0.0.1/::1)은 포트를 무시하고 매칭한다 — Claude Code 같은 CIMD 클라이언트가
    // http://localhost/callback을 등록해 두고 실제로는 매 실행마다 임의 포트로 되돌아오기 때문이다(RFC 8252 §7.3).
    // 비루프백은 https 완전 일치만 허용한다.
    public static boolean redirectUriMatches(String requestedRedirectUri, Set<String> registeredRedirectUris) {
        URI requested;
        try {
            requested = URI.create(requestedRedirectUri);
        } catch (IllegalArgumentException e) {
            return false;
        }

        String requestedHost = stripBrackets(requested.getHost());
        if (isLoopbackHost(requestedHost)) {
            if (requested.getRawQuery() != null || requested.getRawFragment() != null) {
                return false;
            }
            return registeredRedirectUris.stream()
                    .anyMatch(registered -> loopbackMatches(requested, requestedHost, registered));
        }
        return "https".equals(requested.getScheme()) && registeredRedirectUris.contains(requestedRedirectUri);
    }

    private static boolean loopbackMatches(URI requested, String requestedHost, String registeredRedirectUri) {
        URI registered;
        try {
            registered = URI.create(registeredRedirectUri);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String registeredHost = stripBrackets(registered.getHost());
        return isLoopbackHost(registeredHost)
                && Objects.equals(requested.getScheme(), registered.getScheme())
                && requestedHost.equals(registeredHost)
                && Objects.equals(requested.getPath(), registered.getPath());
    }

    // URI.getHost()는 IPv6 주소를 "[::1]"처럼 대괄호를 포함해 돌려주므로 비교 전에 벗겨낸다.
    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    // urn:·상대 경로처럼 호스트가 없는 URI는 getHost()가 null이고, Set.of(...)는 contains(null)에 NPE를 던진다.
    private static boolean isLoopbackHost(String host) {
        return host != null && LOOPBACK_HOSTS.contains(host);
    }
}
