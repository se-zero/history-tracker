package com.history.backend.github.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.repository.GitHubInstallationRepository;
import com.history.backend.github.repository.InstallationTokenCacheView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstallationTokenService {

    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);

    private final GitHubInstallationRepository gitHubInstallationRepository;
    private final GitHubAppClient gitHubAppClient;
    private final CredentialCryptoService credentialCryptoService;
    private final Clock clock;

    @Autowired
    public InstallationTokenService(
            GitHubInstallationRepository gitHubInstallationRepository,
            GitHubAppClient gitHubAppClient,
            CredentialCryptoService credentialCryptoService
    ) {
        this(gitHubInstallationRepository, gitHubAppClient, credentialCryptoService, Clock.systemUTC());
    }

    InstallationTokenService(
            GitHubInstallationRepository gitHubInstallationRepository,
            GitHubAppClient gitHubAppClient,
            CredentialCryptoService credentialCryptoService,
            Clock clock
    ) {
        this.gitHubInstallationRepository = gitHubInstallationRepository;
        this.gitHubAppClient = gitHubAppClient;
        this.credentialCryptoService = credentialCryptoService;
        this.clock = clock;
    }

    @Transactional
    public String getInstallationAccessToken(UUID installationId) {
        InstallationTokenCacheView tokenCache = gitHubInstallationRepository.findTokenCacheById(installationId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));

        if (hasReusableToken(tokenCache)) {
            return credentialCryptoService.decrypt(tokenCache.getEncryptedInstallationToken());
        }

        GitHubInstallation lockedInstallation = gitHubInstallationRepository.findByIdForUpdate(installationId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));

        if (hasReusableToken(lockedInstallation)) {
            return credentialCryptoService.decrypt(lockedInstallation.getEncryptedInstallationToken());
        }

        InstallationAccessToken issuedToken = gitHubAppClient.createInstallationAccessToken(
                lockedInstallation.getInstallationId()
        );
        lockedInstallation.updateInstallationToken(
                credentialCryptoService.encrypt(issuedToken.token()),
                issuedToken.expiresAt()
        );
        return issuedToken.token();
    }

    private boolean hasReusableToken(GitHubInstallation installation) {
        return isReusable(
                installation.getEncryptedInstallationToken(),
                installation.getInstallationTokenExpiresAt()
        );
    }

    private boolean hasReusableToken(InstallationTokenCacheView tokenCache) {
        return isReusable(
                tokenCache.getEncryptedInstallationToken(),
                tokenCache.getInstallationTokenExpiresAt()
        );
    }

    private boolean isReusable(byte[] encryptedToken, Instant expiresAt) {
        return encryptedToken != null
                && expiresAt != null
                && expiresAt.isAfter(Instant.now(clock).plus(REFRESH_SKEW));
    }
}
