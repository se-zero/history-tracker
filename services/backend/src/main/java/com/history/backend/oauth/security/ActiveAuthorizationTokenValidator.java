package com.history.backend.oauth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

// 연결 행이 살아 있는 access token만 통과시키는 검증기 — 철회·탈퇴·갱신 뒤 남은 JWT를 즉시 끊는다
// 만료 판정은 JwtTimestampValidator(시계 오차 허용)가 맡으므로 여기서 다시 하지 않는다.
@Slf4j
public class ActiveAuthorizationTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult REVOKED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token has been revoked.", null));

    private final OAuth2AuthorizationService authorizationService;

    public ActiveAuthorizationTokenValidator(OAuth2AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        OAuth2Authorization authorization;
        try {
            authorization = authorizationService.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
        } catch (DataRetrievalFailureException exception) {
            // 앱 행 정리와 연결 생성이 겹치면 연결은 있는데 앱 행이 없는 상태가 될 수 있다. 이때 500 대신
            // 401을 줘야 앱이 갱신 → invalid_client → 재등록으로 스스로 회복한다. 토큰 값은 로그에 남기지 않는다.
            // 잡는 예외는 조회 실패 전반이라 원인을 단정하지 않고 종류만 남긴다(메시지는 남기지 않는다).
            log.warn("Failed to read the authorization row for an access token; treating it as revoked. cause={}",
                    exception.getClass().getSimpleName());
            return REVOKED;
        }
        if (authorization == null
                || authorization.getAccessToken() == null
                || authorization.getAccessToken().isInvalidated()) {
            return REVOKED;
        }
        return OAuth2TokenValidatorResult.success();
    }
}
