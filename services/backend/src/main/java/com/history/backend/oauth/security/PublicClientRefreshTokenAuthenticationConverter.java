package com.history.backend.oauth.security;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

// 기본 PublicClientAuthenticationConverter는 code_verifier가 있는 authorization_code 교환만
// 공개 클라이언트로 인증해, refresh_token 교환(client_id만 동봉)은 어떤 기본 컨버터도 받지 않아
// invalid_client가 난다 — 그 틈을 메우는 컨버터.
public class PublicClientRefreshTokenAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))) {
            return null;
        }
        String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null || clientIds.length == 0) {
            return null;
        }
        if (clientIds.length > 1) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_REQUEST,
                    "OAuth 2.0 Parameter: client_id",
                    "https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1"));
        }
        // client_secret·client_assertion·Authorization 헤더가 있으면 다른 인증 방식이 쓰이는 요청이므로
        // 그 방식의 기본 컨버터가 처리하도록 양보한다.
        if (request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return null;
        }

        Map<String, Object> additionalParameters = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (!OAuth2ParameterNames.CLIENT_ID.equals(key)) {
                additionalParameters.put(key, values.length == 1 ? values[0] : values);
            }
        });

        return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null, additionalParameters);
    }
}
