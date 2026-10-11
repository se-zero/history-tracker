package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;

// Spring 기본 폐기는 토큰만 무효 표시해 연결 행이 남는다. 폐기가 성공하면 행 전체를 지워 연결을 끊는다.
// RFC 7009 — 모르는 토큰이든 삭제 실패든 응답은 항상 200이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("RevokedAuthorizationRemovingHandler: 폐기 성공 시 연결 행 삭제")
class RevokedAuthorizationRemovingHandlerTest {

    private static final String TOKEN_VALUE = "seed-token";

    @Mock
    private OAuth2AuthorizationService authorizationService;

    @Test
    @DisplayName("인증된 폐기 결과 + 연결 행 있음 → remove 호출, 200")
    void removesAuthorizationWhenRevocationSucceeded() throws Exception {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken(TOKEN_VALUE, null)).thenReturn(authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticatedRevocation());

        verify(authorizationService).remove(authorization);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("미인증 폐기 결과(모르는 토큰) → 조회·삭제 없음, 200")
    void doesNothingWhenRevocationNotAuthenticated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication unauthenticated = new OAuth2TokenRevocationAuthenticationToken(
                TOKEN_VALUE, clientPrincipal(), "refresh_token");

        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, unauthenticated);

        verifyNoInteractions(authorizationService);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("인증됐지만 연결 행이 없으면 remove 없음, 200")
    void doesNotRemoveWhenAuthorizationNotFound() throws Exception {
        when(authorizationService.findByToken(TOKEN_VALUE, null)).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticatedRevocation());

        verify(authorizationService, never()).remove(any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("remove가 DataAccessException을 던져도 삼키고 200")
    void swallowsDataAccessExceptionFromRemove() throws Exception {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken(TOKEN_VALUE, null)).thenReturn(authorization);
        doThrow(new DataRetrievalFailureException("x")).when(authorizationService).remove(authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticatedRevocation());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("findByToken이 DataAccessException을 던져도 삼키고 200")
    void swallowsDataAccessExceptionFromFindByToken() throws Exception {
        when(authorizationService.findByToken(TOKEN_VALUE, null)).thenThrow(new DataRetrievalFailureException("x"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, authenticatedRevocation());

        verify(authorizationService, never()).remove(any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("폐기 토큰이 아닌 Authentication → 아무것도 안 하고 200")
    void doesNothingForOtherAuthenticationType() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication other = new UsernamePasswordAuthenticationToken("user", "pw");

        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, other);

        verifyNoInteractions(authorizationService);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── 헬퍼 ──

    private RevokedAuthorizationRemovingHandler handler() {
        return new RevokedAuthorizationRemovingHandler(authorizationService);
    }

    private OAuth2TokenRevocationAuthenticationToken authenticatedRevocation() {
        OAuth2RefreshToken revoked = new OAuth2RefreshToken(TOKEN_VALUE, Instant.parse("2026-10-10T00:00:00Z"));
        return new OAuth2TokenRevocationAuthenticationToken(revoked, clientPrincipal());
    }

    private OAuth2ClientAuthenticationToken clientPrincipal() {
        return new OAuth2ClientAuthenticationToken("mcp-client", ClientAuthenticationMethod.NONE, null, null);
    }
}
