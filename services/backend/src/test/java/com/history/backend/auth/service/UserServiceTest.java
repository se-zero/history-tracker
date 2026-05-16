package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.github.dto.GitHubUserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

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
}
