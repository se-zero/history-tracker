package com.history.backend.oauth.security;

import java.util.List;

import com.history.backend.oauth.service.McpRegisteredClientPolicy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.converter.OAuth2ClientRegistrationRegisteredClientConverter;

// RFC 7591 DCR 등록 요청 → RegisteredClient. Spring 기본 변환기는 요청을 그대로 옮기므로,
// 여기서 공개 클라이언트(token_endpoint_auth_method=none)로 제한하고 나머지 강제값(scope·PKCE·
// TTL 등)은 McpRegisteredClientPolicy에 위임해 CIMD 등록 경로와 동일하게 맞춘다.
public class PublicClientRegistrationConverter implements Converter<OAuth2ClientRegistration, RegisteredClient> {

    private static final OAuth2ClientRegistrationRegisteredClientConverter DEFAULT_CONVERTER =
            new OAuth2ClientRegistrationRegisteredClientConverter();

    private final McpRegisteredClientPolicy policy;

    public PublicClientRegistrationConverter(McpRegisteredClientPolicy policy) {
        this.policy = policy;
    }

    @Override
    public RegisteredClient convert(OAuth2ClientRegistration registration) {
        // Spring에는 이 에러코드 상수가 없어 RFC 7591 §3.2.2 리터럴을 그대로 쓴다.
        if (!"none".equals(registration.getTokenEndpointAuthenticationMethod())) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "invalid_client_metadata",
                    "token_endpoint_auth_method must be \"none\" (public clients only)",
                    "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2"));
        }

        List<String> redirectUris = registration.getRedirectUris();
        if (redirectUris == null || redirectUris.isEmpty()
                || redirectUris.stream().anyMatch(uri -> !policy.isRegistrableRedirectUri(uri))) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_REDIRECT_URI,
                    "redirect_uris must be https, or http on localhost/127.0.0.1/[::1]",
                    "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2"));
        }

        RegisteredClient base = DEFAULT_CONVERTER.convert(registration);
        String name;
        try {
            name = policy.sanitizeClientName(registration.getClientName(), base.getClientId());
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "invalid_client_metadata",
                    "client_name is invalid.",
                    "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2"));
        }
        Object clientUriClaim = registration.getClaims().get("client_uri");
        String clientUri = clientUriClaim instanceof String s ? s : null;

        return policy.apply(RegisteredClient.from(base).clientName(name), clientUri).build();
    }
}
