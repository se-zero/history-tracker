package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.RefreshToken;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.RefreshTokenRepository;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void issueRefreshTokenStoresOnlyHash() {
        RefreshTokenService service = refreshTokenService();
        User user = user();

        String rawToken = service.issueRefreshToken(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(rawToken).isNotBlank();
        assertThat(captor.getValue().getTokenHash()).isNotEmpty();
    }

    @Test
    void issueRefreshTokenRejectsDeletedUser() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        user.softDelete(Instant.now());

        assertThrows(UnauthorizedException.class, () -> service.issueRefreshToken(user));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void rotateRefreshTokenDeletesOldTokenAndIssuesNewToken() {
        RefreshTokenService service = refreshTokenService();
        RefreshToken oldToken = new RefreshToken(user(), new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(oldToken));

        RefreshTokenIssue issue = service.rotateRefreshToken("old-refresh-token");

        assertThat(issue.user()).isSameAs(oldToken.getUser());
        assertThat(issue.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).delete(oldToken);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void rotateRefreshTokenRejectsInvalidToken() {
        RefreshTokenService service = refreshTokenService();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class, () -> service.rotateRefreshToken("invalid-refresh-token"));
    }

    @Test
    void rotateRefreshTokenDeletesExpiredTokenAndRejectsIt() {
        RefreshTokenService service = refreshTokenService();
        RefreshToken expiredToken = new RefreshToken(user(), new byte[]{1, 2, 3}, Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expiredToken));

        assertThrows(UnauthorizedException.class, () -> service.rotateRefreshToken("expired-refresh-token"));
        verify(refreshTokenRepository).delete(expiredToken);
    }

    @Test
    void rotateRefreshTokenDeletesTokenAndRejectsDeletedUser() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        user.softDelete(Instant.now());
        RefreshToken refreshToken = new RefreshToken(user, new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(refreshToken));

        assertThrows(UnauthorizedException.class, () -> service.rotateRefreshToken("deleted-user-refresh-token"));
        verify(refreshTokenRepository).delete(refreshToken);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void revokeRefreshTokenDeletesTokenWhenItExists() {
        RefreshTokenService service = refreshTokenService();
        RefreshToken refreshToken = new RefreshToken(user(), new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(refreshToken));

        service.revokeRefreshToken("refresh-token");

        verify(refreshTokenRepository).delete(refreshToken);
    }

    @Test
    void revokeAllRefreshTokensDeletesTokensByUserId() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        service.revokeAllRefreshTokens(user);

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
    }

    private RefreshTokenService refreshTokenService() {
        return new RefreshTokenService(
                refreshTokenRepository,
                new JwtProperties("test-secret", Duration.ofMinutes(15), Duration.ofDays(14))
        );
    }

    private User user() {
        return new User("github", "12345", "octocat@example.com", "Octocat", null);
    }
}
