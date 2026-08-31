package com.history.backend.github.service;

import java.time.Clock;
import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.domain.GitHubUserCredentialEntity;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.repository.GitHubUserCredentialRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 로그인 시 사용자 GitHub OAuth 토큰 암호화 저장·캐시 갱신·grant 폐기
@Service
public class GitHubUserTokenService {

    private static final String REAUTHORIZATION_REQUIRED = "GitHub reauthorization required.";

    private final GitHubUserCredentialRepository gitHubUserCredentialRepository;
    private final GitHubUserCredentialCodec gitHubUserCredentialCodec;
    private final GitHubOAuthClient gitHubOAuthClient;
    private final GitHubAppProperties gitHubAppProperties;
    private final Clock clock;
    // REQUIRES_NEW로 고정한다 — 호출부가 이미 트랜잭션을 열어 둔 채로 이 트랜잭션이 REQUIRED로
    // 합류하면, 갱신이 401로 실패해도 예외가 나는 즉시 롤백되지 않고 "rollback-only" 표시만 남아
    // PESSIMISTIC_WRITE 행 잠금이 계속 살아 있는다. 그 상태에서 revertTransactionTemplate이 같은
    // 행을 지우려 들면 자기 자신의 잠금과 교착된다.
    private final TransactionTemplate transactionTemplate;
    // 갱신 실패 시 행 삭제는 실패한 갱신 트랜잭션과 절대 겹치면 안 된다 — 그 트랜잭션이 쥔
    // PESSIMISTIC_WRITE 행 잠금이 살아있는 채로 같은 행을 삭제하려 들면 자기 자신과 교착된다.
    private final TransactionTemplate revertTransactionTemplate;

    @Autowired
    public GitHubUserTokenService(
            GitHubUserCredentialRepository gitHubUserCredentialRepository,
            GitHubUserCredentialCodec gitHubUserCredentialCodec,
            GitHubOAuthClient gitHubOAuthClient,
            GitHubAppProperties gitHubAppProperties,
            PlatformTransactionManager transactionManager
    ) {
        this(
                gitHubUserCredentialRepository,
                gitHubUserCredentialCodec,
                gitHubOAuthClient,
                gitHubAppProperties,
                transactionManager,
                Clock.systemUTC()
        );
    }

    GitHubUserTokenService(
            GitHubUserCredentialRepository gitHubUserCredentialRepository,
            GitHubUserCredentialCodec gitHubUserCredentialCodec,
            GitHubOAuthClient gitHubOAuthClient,
            GitHubAppProperties gitHubAppProperties,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.gitHubUserCredentialRepository = gitHubUserCredentialRepository;
        this.gitHubUserCredentialCodec = gitHubUserCredentialCodec;
        this.gitHubOAuthClient = gitHubOAuthClient;
        this.gitHubAppProperties = gitHubAppProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.revertTransactionTemplate = new TransactionTemplate(transactionManager);
        this.revertTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 재로그인 시 같은 user_id PK를 insert하면 충돌하므로, 있으면 암호문만 덮어쓴다
    public void save(UUID userId, GitHubAccessTokenResponse tokens) {
        GitHubUserCredential credential = new GitHubUserCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                clock.instant().plusSeconds(tokens.expiresIn()),
                clock.instant().plusSeconds(tokens.refreshTokenExpiresIn())
        );
        byte[] encrypted = gitHubUserCredentialCodec.encrypt(credential);

        gitHubUserCredentialRepository.findById(userId).ifPresentOrElse(
                existing -> {
                    existing.updateCredential(encrypted);
                    gitHubUserCredentialRepository.save(existing);
                },
                () -> gitHubUserCredentialRepository.save(new GitHubUserCredentialEntity(userId, encrypted))
        );
    }

    // 캐시된 토큰 재사용 또는 refresh token으로 갱신
    public String getAccessToken(UUID userId) {
        try {
            return transactionTemplate.execute(status -> refreshIfNeeded(userId));
        } catch (GitHubRefreshRejectedException exception) {
            // 이 시점엔 위 트랜잭션이 이미 롤백되어 PESSIMISTIC_WRITE 잠금이 풀린 뒤다 —
            // 별도 트랜잭션으로 지워도 자기 자신의 잠금과 교착되지 않는다.
            revertTransactionTemplate.executeWithoutResult(status ->
                    gitHubUserCredentialRepository.deleteById(userId));
            throw new ForbiddenException(REAUTHORIZATION_REQUIRED);
        }
    }

    // 사용자 GitHub App grant 폐기. 행이 없거나 refresh가 이미 폐기된 경우는 지울 대상이 없다.
    public boolean revokeGrant(UUID userId) {
        try {
            String access = getAccessToken(userId);
            return gitHubOAuthClient.revokeGrant(access);
        } catch (ForbiddenException exception) {
            return true;
        } catch (BadGatewayException exception) {
            return false;
        }
    }

    private String refreshIfNeeded(UUID userId) {
        GitHubUserCredentialEntity entity = gitHubUserCredentialRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenException(REAUTHORIZATION_REQUIRED));

        GitHubUserCredential credential = gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential());
        if (isReusable(credential)) {
            return credential.accessToken();
        }

        GitHubUserCredentialEntity lockedEntity = gitHubUserCredentialRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ForbiddenException(REAUTHORIZATION_REQUIRED));
        GitHubUserCredential lockedCredential = gitHubUserCredentialCodec.decrypt(lockedEntity.getEncryptedCredential());
        // 잠금 대기 중 다른 트랜잭션이 갱신했을 수 있어 재확인 (double-checked locking)
        if (isReusable(lockedCredential)) {
            return lockedCredential.accessToken();
        }

        GitHubAccessTokenResponse refreshed;
        try {
            refreshed = gitHubOAuthClient.refresh(lockedCredential.refreshToken());
        } catch (UnauthorizedException exception) {
            // refresh token이 폐기됨 — 재발급 수단이 없다. 트랜잭션 콜백은 바깥에서 행을 지우도록
            // 내부 전용 unchecked 예외로 전달한다(잠금이 풀린 뒤에야 삭제해야 교착되지 않는다).
            throw new GitHubRefreshRejectedException();
        }

        GitHubUserCredential newCredential = new GitHubUserCredential(
                refreshed.accessToken(),
                refreshed.refreshToken(),
                clock.instant().plusSeconds(refreshed.expiresIn()),
                clock.instant().plusSeconds(refreshed.refreshTokenExpiresIn())
        );
        lockedEntity.updateCredential(gitHubUserCredentialCodec.encrypt(newCredential));
        return newCredential.accessToken();
    }

    // 사용 중 만료를 막기 위해 만료 전 refreshSkew만큼 여유를 두고 갱신 대상으로 처리
    private boolean isReusable(GitHubUserCredential credential) {
        return credential.expiresAt() != null
                && credential.expiresAt().isAfter(clock.instant().plus(gitHubAppProperties.refreshSkew()));
    }

    private static final class GitHubRefreshRejectedException extends RuntimeException {
    }
}
