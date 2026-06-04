package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.dto.GitHubUserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Mock
    private UserRepository userRepository;

    @Test
    void upsertGitHubUserReloadsUserWhenConcurrentInsertWins() {
        UserService userService = new UserService(userRepository);
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
    void deactivateUserSoftDeletesActiveUser() {
        UserService userService = new UserService(userRepository);
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));

        userService.deactivateUser(USER_ID);

        assertThat(user.getDeletedAt()).isNotNull();
    }

    @Test
    void deactivateUserRejectsDeletedOrMissingUser() {
        UserService userService = new UserService(userRepository);
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> userService.deactivateUser(USER_ID));
    }
}
