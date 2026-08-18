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

// GitHub installation access token 캐싱 발급 (암호화 후 DB 저장)
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

    // 캐시된 토큰 재사용 또는 신규 발급
    @Transactional
    public String getInstallationAccessToken(UUID installationId) {
        // 잠금 없는 경량 projection으로 먼저 확인해 유효 토큰이면 잠금 비용 회피
        InstallationTokenCacheView tokenCache = gitHubInstallationRepository.findTokenCacheById(installationId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));

        if (hasReusableToken(tokenCache)) {
            return credentialCryptoService.decrypt(tokenCache.getEncryptedInstallationToken());
        }

        GitHubInstallation lockedInstallation = gitHubInstallationRepository.findByIdForUpdate(installationId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));

        // 잠금 대기 중 다른 트랜잭션이 갱신했을 수 있어 재확인 (double-checked locking)
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

    // 외부 GitHub installation ID 기준 유효 access token 캐시 보장
    @Transactional
    public void ensureInstallationAccessToken(Long installationId) {
        UUID installationRowId = gitHubInstallationRepository.findByInstallationId(installationId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."))
                .getId();
        getInstallationAccessToken(installationRowId);
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

    // 사용 중 만료를 막기 위해 만료 5분 전부터 갱신 대상으로 처리
    private boolean isReusable(byte[] encryptedToken, Instant expiresAt) {
        return encryptedToken != null
                && expiresAt != null
                && expiresAt.isAfter(Instant.now(clock).plus(REFRESH_SKEW));
    }
}
