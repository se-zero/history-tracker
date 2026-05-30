package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.repository.GitHubInstallationRepository;
import com.history.backend.github.repository.InstallationTokenCacheView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InstallationTokenServiceTest {

    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");
    private static final Instant NOW = Instant.parse("2026-05-30T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Mock
    private GitHubAppClient gitHubAppClient;

    @Mock
    private CredentialCryptoService credentialCryptoService;

    @Test
    void returnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        InstallationTokenService service = service();
        GitHubInstallation installation = installation();
        byte[] encryptedToken = new byte[]{1, 2, 3};
        installation.updateInstallationToken(encryptedToken, NOW.plusSeconds(301));

        when(gitHubInstallationRepository.findTokenCacheById(INSTALLATION_ID))
                .thenReturn(Optional.of(tokenCache(encryptedToken, NOW.plusSeconds(301))));
        when(credentialCryptoService.decrypt(encryptedToken))
                .thenReturn("cached-token");

        String result = service.getInstallationAccessToken(INSTALLATION_ID);

        assertThat(result).isEqualTo("cached-token");
        verify(gitHubInstallationRepository, never()).findByIdForUpdate(INSTALLATION_ID);
        verify(gitHubAppClient, never()).createInstallationAccessToken(98765L);
        verify(credentialCryptoService, never()).encrypt("cached-token");
    }

    @Test
    void refreshesTokenWhenCacheIsMissing() {
        InstallationTokenService service = service();
        GitHubInstallation installation = installation();
        byte[] encryptedToken = new byte[]{9, 8, 7};
        Instant expiresAt = NOW.plusSeconds(3600);

        when(gitHubInstallationRepository.findTokenCacheById(INSTALLATION_ID))
                .thenReturn(Optional.of(tokenCache(null, null)));
        when(gitHubInstallationRepository.findByIdForUpdate(INSTALLATION_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubAppClient.createInstallationAccessToken(98765L))
                .thenReturn(new InstallationAccessToken("fresh-token", expiresAt));
        when(credentialCryptoService.encrypt("fresh-token"))
                .thenReturn(encryptedToken);

        String result = service.getInstallationAccessToken(INSTALLATION_ID);

        assertThat(result).isEqualTo("fresh-token");
        assertThat(installation.getEncryptedInstallationToken()).isEqualTo(encryptedToken);
        assertThat(installation.getInstallationTokenExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void refreshesTokenWhenExpiryIsInsideRefreshSkew() {
        InstallationTokenService service = service();
        GitHubInstallation installation = installation();
        byte[] oldEncryptedToken = new byte[]{1, 2, 3};
        byte[] newEncryptedToken = new byte[]{4, 5, 6};
        Instant newExpiresAt = NOW.plusSeconds(3600);
        installation.updateInstallationToken(oldEncryptedToken, NOW.plusSeconds(300));

        when(gitHubInstallationRepository.findTokenCacheById(INSTALLATION_ID))
                .thenReturn(Optional.of(tokenCache(oldEncryptedToken, NOW.plusSeconds(300))));
        when(gitHubInstallationRepository.findByIdForUpdate(INSTALLATION_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubAppClient.createInstallationAccessToken(98765L))
                .thenReturn(new InstallationAccessToken("fresh-token", newExpiresAt));
        when(credentialCryptoService.encrypt("fresh-token"))
                .thenReturn(newEncryptedToken);

        String result = service.getInstallationAccessToken(INSTALLATION_ID);

        assertThat(result).isEqualTo("fresh-token");
        assertThat(installation.getEncryptedInstallationToken()).isEqualTo(newEncryptedToken);
        assertThat(installation.getInstallationTokenExpiresAt()).isEqualTo(newExpiresAt);
        verify(credentialCryptoService, never()).decrypt(oldEncryptedToken);
    }

    @Test
    void reusesTokenRefreshedByAnotherTransactionAfterLock() {
        InstallationTokenService service = service();
        GitHubInstallation staleInstallation = installation();
        staleInstallation.updateInstallationToken(new byte[]{1, 2, 3}, NOW.plusSeconds(300));
        GitHubInstallation refreshedInstallation = installation();
        byte[] refreshedEncryptedToken = new byte[]{4, 5, 6};
        refreshedInstallation.updateInstallationToken(refreshedEncryptedToken, NOW.plusSeconds(3600));

        when(gitHubInstallationRepository.findTokenCacheById(INSTALLATION_ID))
                .thenReturn(Optional.of(tokenCache(new byte[]{1, 2, 3}, NOW.plusSeconds(300))));
        when(gitHubInstallationRepository.findByIdForUpdate(INSTALLATION_ID))
                .thenReturn(Optional.of(refreshedInstallation));
        when(credentialCryptoService.decrypt(refreshedEncryptedToken))
                .thenReturn("refreshed-token");

        String result = service.getInstallationAccessToken(INSTALLATION_ID);

        assertThat(result).isEqualTo("refreshed-token");
        verify(gitHubAppClient, never()).createInstallationAccessToken(98765L);
    }

    @Test
    void rejectsMissingInstallation() {
        InstallationTokenService service = service();
        when(gitHubInstallationRepository.findTokenCacheById(INSTALLATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInstallationAccessToken(INSTALLATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub installation not found.");
    }

    private InstallationTokenService service() {
        return new InstallationTokenService(
                gitHubInstallationRepository,
                gitHubAppClient,
                credentialCryptoService,
                CLOCK
        );
    }

    private GitHubInstallation installation() {
        User installer = new User("github", "12345", "octocat@example.com", "Octocat", null);
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        return installation;
    }

    private InstallationTokenCacheView tokenCache(byte[] encryptedInstallationToken, Instant expiresAt) {
        return new InstallationTokenCacheView() {
            @Override
            public byte[] getEncryptedInstallationToken() {
                return encryptedInstallationToken;
            }

            @Override
            public Instant getInstallationTokenExpiresAt() {
                return expiresAt;
            }
        };
    }
}
