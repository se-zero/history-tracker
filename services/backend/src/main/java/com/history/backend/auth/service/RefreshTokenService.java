package com.history.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import com.history.backend.auth.domain.RefreshToken;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.RefreshTokenRepository;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.security.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    // refresh token 발급 및 hash 저장
    @Transactional
    public String issueRefreshToken(User user) {
        if (user.getDeletedAt() != null) {
            throw new UnauthorizedException("User is deactivated.");
        }

        String rawToken = generateRefreshToken();
        // DB 유출 대비 원문 대신 hash만 저장
        refreshTokenRepository.save(new RefreshToken(
                user,
                sha256(rawToken),
                Instant.now().plus(jwtProperties.refreshTokenTtl())
        ));
        return rawToken;
    }

    // refresh token 1회용 rotation (사용된 토큰 폐기 후 재발급)
    @Transactional
    public RefreshTokenIssue rotateRefreshToken(String rawToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token."));

        if (!refreshToken.getExpiresAt().isAfter(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new UnauthorizedException("Expired refresh token.");
        }

        User user = refreshToken.getUser();
        refreshTokenRepository.delete(refreshToken);

        // soft-deleted user는 grace period 복구 전까지 재발급 불가
        if (user.getDeletedAt() != null) {
            throw new UnauthorizedException("User is deactivated.");
        }

        return new RefreshTokenIssue(user, issueRefreshToken(user));
    }

    @Transactional
    public void revokeRefreshToken(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .ifPresent(refreshTokenRepository::delete);
    }

    @Transactional
    public void revokeAllRefreshTokens(User user) {
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    private String generateRefreshToken() {
        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
