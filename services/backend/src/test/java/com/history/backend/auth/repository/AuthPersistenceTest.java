package com.history.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.history.backend.auth.domain.RefreshToken;
import com.history.backend.auth.domain.User;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.repository.GitHubInstallationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthPersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Test
    void saveAndFindAuthFoundationEntities() {
        User user = userRepository.save(new User(
                "github",
                "12345",
                "octocat@example.com",
                "Octocat",
                "https://github.com/images/error/octocat_happy.gif"
        ));

        RefreshToken refreshToken = refreshTokenRepository.save(new RefreshToken(
                user,
                new byte[]{1, 2, 3},
                Instant.now().plusSeconds(3600)
        ));

        GitHubInstallation installation = gitHubInstallationRepository.save(new GitHubInstallation(
                98765L,
                "Organization",
                "acme",
                user
        ));

        assertThat(userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("github", "12345"))
                .contains(user);
        assertThat(refreshTokenRepository.findByTokenHash(new byte[]{1, 2, 3}))
                .contains(refreshToken);
        assertThat(gitHubInstallationRepository.findByInstallationId(98765L))
                .contains(installation);
    }
}
