package com.history.backend.oauth.security;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

// 폐기 요청(RFC 7009)에는 grant_type이 없어 기본 공개 클라이언트 컨버터(code_verifier 필요)도
// refresh 컨버터(grant_type=refresh_token 필요)도 받지 않아 invalid_client가 난다 — 그 틈을 메우는 컨버터.
// Bearer 폴백은 일부러 받지 않는다: client_id 없이 클라이언트를 인증할 근거가 없다.
public class PublicClientRevocationAuthenticationConverter implements AuthenticationConverter {

    private final String revocationEndpointPath;

    public PublicClientRevocationAuthenticationConverter(String revocationEndpointPath) {
        this.revocationEndpointPath = revocationEndpointPath;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!revocationEndpointPath.equals(pathWithinApplication(request))) {
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
        if (request.getParameter(OAuth2ParameterNames.TOKEN) == null) {
            return null;
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

    // 앱에 context-path가 생기면 getRequestURI()는 그 접두사를 포함한다 — Spring 폐기 필터의 매처처럼 앱 안의 경로로 비교한다.
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
