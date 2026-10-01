package com.history.backend.oauth.service;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

import com.history.backend.oauth.McpOAuthProperties;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

// CIMD·DCR 두 등록 경로가 공통으로 강제하는 값 — 경로마다 다른 정책을 적용하면 같은 방식(공개
// 클라이언트)으로 들어온 클라이언트인데 권한이 갈라지는 문제가 생긴다. 공개 클라이언트만 받고
// (NONE, secret 없음), refresh token 회전을 강제한다(공개 클라이언트 회전은 MCP 규격 MUST).
// Spring 동의 화면은 끈다 — 동의는 SPA가 대신 받는다(B3).
@Component
public class McpRegisteredClientPolicy {

    public static final String CLIENT_URI_SETTING = "client_uri";
    public static final String SCOPE = "mcp:query";
    public static final int MAX_CLIENT_NAME_LENGTH = 100;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cntrl}");

    private final McpOAuthProperties properties;

    public McpRegisteredClientPolicy(McpOAuthProperties properties) {
        this.properties = properties;
    }

    public RegisteredClient.Builder apply(RegisteredClient.Builder builder, String clientUri) {
        builder.clientAuthenticationMethods(methods -> {
                    methods.clear();
                    methods.add(ClientAuthenticationMethod.NONE);
                })
                .clientSecret(null)
                .authorizationGrantTypes(grantTypes -> {
                    grantTypes.clear();
                    grantTypes.add(AuthorizationGrantType.AUTHORIZATION_CODE);
                    grantTypes.add(AuthorizationGrantType.REFRESH_TOKEN);
                })
                .scopes(scopes -> {
                    scopes.clear();
                    scopes.add(SCOPE);
                });

        ClientSettings.Builder clientSettings = ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false);
        // 동의 화면이 client_uri를 링크로 그리므로 javascript: 등 스킴 주입을 막는다.
        if (clientUri != null && (clientUri.startsWith("http://") || clientUri.startsWith("https://"))) {
            clientSettings.setting(CLIENT_URI_SETTING, clientUri);
        }
        builder.clientSettings(clientSettings.build());

        builder.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(properties.accessTokenTtl())
                .refreshTokenTimeToLive(properties.refreshTokenTtl())
                .reuseRefreshTokens(false)
                .build());

        return builder;
    }

    // 루프백(localhost/127.0.0.1/[::1])의 http는 포트 무관 허용, 그 외는 https만 등록 가능하다.
    // McpAuthorizationRequestValidator.redirectUriMatches와 판정 기준은 같지만, 이쪽은 "등록 가능
    // 여부"라는 다른 질문이라 독립적으로 구현한다.
    public boolean isRegistrableRedirectUri(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(redirectUri);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getRawFragment() != null) {
            return false;
        }
        String scheme = uri.getScheme();
        if ("https".equals(scheme)) {
            return true;
        }
        if ("http".equals(scheme)) {
            // http:///cb 처럼 호스트가 없으면 getHost()가 null이고, Set.of(...)는 contains(null)에 NPE를 던진다.
            String host = stripBrackets(uri.getHost());
            return host != null && LOOPBACK_HOSTS.contains(host);
        }
        return false;
    }

    public String sanitizeClientName(String rawName, String fallback) {
        if (rawName == null || rawName.isBlank()) {
            return fallback;
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > MAX_CLIENT_NAME_LENGTH || CONTROL_CHARACTER.matcher(trimmed).find()) {
            throw new IllegalArgumentException("client_name is invalid: too long or contains control characters.");
        }
        return trimmed;
    }

    public static String clientUriOf(RegisteredClient client) {
        return client.getClientSettings().getSetting(CLIENT_URI_SETTING);
    }

    // URI.getHost()는 IPv6 주소를 "[::1]"처럼 대괄호를 포함해 돌려주므로 비교 전에 벗겨낸다.
    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
