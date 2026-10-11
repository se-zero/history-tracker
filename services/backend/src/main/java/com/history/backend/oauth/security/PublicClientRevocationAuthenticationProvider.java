package com.history.backend.oauth.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

// PublicClientRevocationAuthenticationConverter가 만든 인증되지 않은 토큰을 받아 client_id가
// 등록된 공개 클라이언트인지 확인한다.
public class PublicClientRevocationAuthenticationProvider implements AuthenticationProvider {

    private final RegisteredClientRepository registeredClientRepository;

    public PublicClientRevocationAuthenticationProvider(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        // grant_type이 있으면 토큰 교환 요청이므로 refresh 프로바이더·기본 프로바이더 몫이다.
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || !token.getAdditionalParameters().containsKey(OAuth2ParameterNames.TOKEN)
                || token.getAdditionalParameters().containsKey(OAuth2ParameterNames.GRANT_TYPE)) {
            return null;
        }

        RegisteredClient client = registeredClientRepository.findByClientId((String) token.getPrincipal());
        if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_CLIENT,
                    "client authentication failed: client_id",
                    "https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1"));
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
