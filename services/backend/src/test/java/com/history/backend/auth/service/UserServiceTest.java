package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.dto.GitHubUserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService: 사용자 생성·조회·탈퇴")
class UserServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("동시 삽입 충돌 시 재조회로 사용자 반환")
    void upsertGitHubUserReloadsUserWhenConcurrentInsertWins() {
        UserService userService = userService();
        GitHubUserResponse gitHubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        User savedByOtherRequest = new User("github", "12345", "octocat@example.com", "Octocat", null);

        when(userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("github", "12345"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedByOtherRequest));
        when(userRepository.findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc("github", "12345"))
                .thenReturn(Optional.empty());
        when(userRepository.insertActiveUserIfAbsent(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        User result = userService.upsertGitHubUser(gitHubUser);

        assertThat(result).isSameAs(savedByOtherRequest);
    }

    @Test
    @DisplayName("유예 기간 내 soft-deleted 사용자 복구")
    void upsertGitHubUserRestoresDeletedUserWithinGracePeriod() {
        UserService userService = userService();
        GitHubUserResponse gitHubUser = new GitHubUserResponse(
                12345L,
                "octocat",
                "Restored Octocat",
                "restored@example.com",
                "https://github.com/images/error/octocat_happy.gif"
        );
        User deletedUser = new User("github", "12345", "old@example.com", "Old Octocat", null);
        deletedUser.softDelete(Instant.now().minus(Duration.ofDays(1)));

        when(userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("github", "12345"))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc("github", "12345"))
                .thenReturn(Optional.of(deletedUser));

        User result = userService.upsertGitHubUser(gitHubUser);

        assertThat(result).isSameAs(deletedUser);
        assertThat(result.getDeletedAt()).isNull();
        assertThat(result.getEmail()).isEqualTo("restored@example.com");
        assertThat(result.getDisplayName()).isEqualTo("Restored Octocat");
    }

    @Test
    @DisplayName("유예 기간 초과 soft-deleted 사용자 → 신규 사용자 생성")
    void upsertGitHubUserCreatesNewUserWhenDeletedUserIsPastGracePeriod() {
        UserService userService = userService();
        GitHubUserResponse gitHubUser = new GitHubUserResponse(12345L, "octocat", "Octocat", null, null);
        User expiredDeletedUser = new User("github", "12345", "old@example.com", "Old Octocat", null);
        expiredDeletedUser.softDelete(Instant.now().minus(Duration.ofDays(31)));
        UUID newUserId = UUID.fromString("801db2d0-f3dd-4dfc-ae2a-8ea12678ba59");
        User newUser = new User("github", "12345", "octocat@example.com", "Octocat", null);

        when(userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("github", "12345"))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc("github", "12345"))
                .thenReturn(Optional.of(expiredDeletedUser));
        when(userRepository.insertActiveUserIfAbsent(
                "github",
                "12345",
                "12345+octocat@users.noreply.github.com",
                "Octocat",
                null
        )).thenReturn(Optional.of(newUserId));
        when(userRepository.findById(newUserId)).thenReturn(Optional.of(newUser));

        User result = userService.upsertGitHubUser(gitHubUser);

        assertThat(result).isSameAs(newUser);
        assertThat(expiredDeletedUser.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("사용자 탈퇴 시 soft delete 및 갱신 토큰 일괄 폐기")
    void deactivateUserSoftDeletesActiveUserAndRevokesRefreshTokens() {
        UserService userService = userService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));

        userService.deactivateUser(USER_ID);

        assertThat(user.getDeletedAt()).isNotNull();
        verify(refreshTokenService).revokeAllRefreshTokens(user);
    }

    @Test
    @DisplayName("탈퇴·미존재 사용자 탈퇴 요청 거부")
    void deactivateUserRejectsDeletedOrMissingUser() {
        UserService userService = userService();
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> userService.deactivateUser(USER_ID));
        verifyNoInteractions(refreshTokenService);
    }

    private UserService userService() {
        return new UserService(userRepository, refreshTokenService);
    }
}
