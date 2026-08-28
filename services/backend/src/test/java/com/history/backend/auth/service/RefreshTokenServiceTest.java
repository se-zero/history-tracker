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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService: 갱신 토큰 발급·교체·폐기")
class RefreshTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("갱신 토큰 발급 시 hash만 저장")
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
    @DisplayName("탈퇴 사용자에게 갱신 토큰 발급 거부")
    void issueRefreshTokenRejectsDeletedUser() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        user.softDelete(Instant.now());

        assertThrows(UnauthorizedException.class, () -> service.issueRefreshToken(user));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("갱신 토큰 교체 시 기존 행은 지우지 않고 replaced_at만 남긴 뒤 신규 발급")
    void rotateRefreshTokenMarksOldTokenReplacedAndIssuesNewToken() {
        RefreshTokenService service = refreshTokenService();
        RefreshToken oldToken = new RefreshToken(user(), new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(oldToken));

        RefreshTokenIssue issue = service.rotateRefreshToken("old-refresh-token");

        assertThat(issue.user()).isSameAs(oldToken.getUser());
        assertThat(issue.refreshToken()).isNotBlank();
        assertThat(oldToken.getReplacedAt()).isNotNull();
        verify(refreshTokenRepository, never()).delete(oldToken);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("유효하지 않은 갱신 토큰 교체 거부 — 전 세션을 끊지 않는다")
    void rotateRefreshTokenRejectsInvalidTokenWithoutRevokingAll() {
        RefreshTokenService service = refreshTokenService();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class, () -> service.rotateRefreshToken("invalid-refresh-token"));
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("만료 갱신 토큰 폐기 후 교체 거부")
    void rotateRefreshTokenDeletesExpiredTokenAndRejectsIt() {
        RefreshTokenService service = refreshTokenService();
        RefreshToken expiredToken = new RefreshToken(user(), new byte[]{1, 2, 3}, Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expiredToken));

        assertThrows(UnauthorizedException.class, () -> service.rotateRefreshToken("expired-refresh-token"));
        verify(refreshTokenRepository).delete(expiredToken);
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("탈퇴 사용자 갱신 토큰 폐기 후 교체 거부")
    void rotateRefreshTokenDeletesTokenAndRejectsDeletedUser() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        user.softDelete(Instant.now());
        RefreshToken refreshToken = new RefreshToken(user, new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(refreshToken));

        assertThrows(UnauthorizedException.class, () -> service.rotateRefreshToken("deleted-user-refresh-token"));
        verify(refreshTokenRepository).delete(refreshToken);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("방금 교체된 토큰을 유예 시간 안에 다시 내면 401만 — 탭 경합으로 전 세션을 끊지 않는다")
    void rotateRefreshTokenRejectsRecentlyReplacedTokenWithoutRevokingAll() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        RefreshToken replaced = new RefreshToken(user, new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        ReflectionTestUtils.setField(replaced, "replacedAt", Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(replaced));

        UnauthorizedException thrown = assertThrows(
                UnauthorizedException.class,
                () -> service.rotateRefreshToken("already-rotated")
        );
        assertThat(thrown.clearsRefreshCookie()).isFalse();
        verify(refreshTokenRepository, never()).deleteByUserId(any());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("이미 교체된 토큰을 유예 시간 밖에 다시 내면 해당 사용자 세션을 전부 끊는다")
    void rotateRefreshTokenRevokesAllSessionsWhenReplacedTokenIsReusedAfterGrace() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        RefreshToken replaced = new RefreshToken(user, new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        ReflectionTestUtils.setField(replaced, "replacedAt", Instant.now().minusSeconds(30));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(replaced));

        UnauthorizedException thrown = assertThrows(
                UnauthorizedException.class,
                () -> service.rotateRefreshToken("stolen-refresh-token")
        );
        assertThat(thrown.clearsRefreshCookie()).isTrue();
        verify(refreshTokenRepository).deleteByUserId(USER_ID);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("갱신 토큰 폐기 성공")
    void revokeRefreshTokenDeletesTokenWhenItExists() {
        RefreshTokenService service = refreshTokenService();
        RefreshToken refreshToken = new RefreshToken(user(), new byte[]{1, 2, 3}, Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(refreshToken));

        service.revokeRefreshToken("refresh-token");

        verify(refreshTokenRepository).delete(refreshToken);
    }

    @Test
    @DisplayName("사용자의 모든 갱신 토큰 일괄 폐기")
    void revokeAllRefreshTokensDeletesTokensByUserId() {
        RefreshTokenService service = refreshTokenService();
        User user = user();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        service.revokeAllRefreshTokens(user);

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("만료된 갱신 토큰 행을 삭제한다")
    void purgeExpiredRefreshTokensDeletesRowsPastExpiry() {
        RefreshTokenService service = refreshTokenService();

        when(refreshTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(7L);

        assertThat(service.purgeExpiredRefreshTokens()).isEqualTo(7);
        verify(refreshTokenRepository).deleteByExpiresAtBefore(any());
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
