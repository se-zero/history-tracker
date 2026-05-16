package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubInstallationAccountResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.repository.GitHubInstallationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GitHubInstallationServiceTest {

    @Mock
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Test
    void upsertInstallationReloadsInstallationWhenConcurrentInsertWins() {
        GitHubInstallationService service = new GitHubInstallationService(gitHubInstallationRepository);
        User installer = new User("github", "12345", "octocat@example.com", "Octocat", null);
        ReflectionTestUtils.setField(installer, "id", UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50"));
        GitHubInstallationResponse response = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("acme", "Organization")
        );
        GitHubInstallation savedByOtherRequest = new GitHubInstallation(98765L, "Organization", "acme", installer);

        when(gitHubInstallationRepository.findByInstallationId(98765L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedByOtherRequest));
        when(gitHubInstallationRepository.insertInstallationIfAbsent(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        GitHubInstallation result = service.upsertInstallation(installer, response);

        assertThat(result).isSameAs(savedByOtherRequest);
    }
}
