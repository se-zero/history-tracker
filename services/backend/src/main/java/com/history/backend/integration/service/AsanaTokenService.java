package com.history.backend.integration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.asana.AsanaProperties;
import com.history.backend.asana.service.AsanaOAuthClient;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// Asana access token 캐싱 갱신 — LinearTokenService의 알고리즘을 그대로 옮긴 것.
// Asana도 access token 만료 시각이 암호화된 credential JSON 안에 있어 잠금 없는 선조회도 복호화가 필요하다.
// 단, Asana refresh token은 회전하지 않는다 — 갱신 응답에 새 값이 없으므로 기존 값을 보존해야
// 다음 갱신이 성공한다(Linear는 반대로 회전된 새 값을 반드시 저장해야 한다).
// 회전이 없어도 잠금·REQUIRES_NEW 구조는 그대로 둔다 — 동시 요청이 만료 임박 토큰을 각자 갱신하려
// 들면 Asana API를 중복 호출하게 되고, provider마다 이 구조를 다르게 가져가면 다음 provider를
// 추가할 때마다 "이번엔 잠가야 하나"를 매번 새로 판단해야 한다.
@Service
public class AsanaTokenService {

    private final IntegrationRepository integrationRepository;
    private final AsanaOAuthClient asanaOAuthClient;
    private final AsanaCredentialCodec asanaCredentialCodec;
    private final AsanaProperties asanaProperties;
    private final Clock clock;
    // REQUIRES_NEW로 고정한다 — 호출부가 이미 트랜잭션을 열어 둔 채로(예: 향후 누군가 getAccessToken을
    // @Transactional 메서드 안에서 부르는 경우) 이 트랜잭션이 REQUIRED로 합류하면, 갱신이 401로
    // 실패해도 예외가 나는 즉시 롤백되지 않고 "rollback-only" 표시만 남아 PESSIMISTIC_WRITE 행 잠금이
    // 계속 살아 있는다. 그 상태에서 revertTransactionTemplate(REQUIRES_NEW)이 그 바깥 트랜잭션을
    // 중단시키고 같은 행을 UPDATE하려 들면 자기 자신의 잠금과 교착된다 — 이는 아래 되돌리기 트랜잭션을
    // 분리한 이유와 동일한 교착이 호출 문맥에 따라 재현되는 것이므로, 항상 독립적으로 시작·종료되게
    // REQUIRES_NEW로 고정해 호출 문맥과 무관하게 만든다.
    private final TransactionTemplate transactionTemplate;
    // 갱신 실패 시 pending 되돌리기는 실패한 갱신 트랜잭션과 절대 겹치면 안 된다 — 그 트랜잭션이 쥔
    // PESSIMISTIC_WRITE 행 잠금이 살아있는 채로 같은 행을 갱신하려 들면 자기 자신과 교착된다.
    // getAccessToken에서 갱신 트랜잭션이 롤백을 마치고 완전히 끝난 뒤 순차적으로 이 템플릿을 호출해
    // (같은 빈 안에서 @Transactional(REQUIRES_NEW)를 self-invocation으로 호출하는 대신) 안전하게 별도 커밋한다.
    private final TransactionTemplate revertTransactionTemplate;

    @Autowired
    public AsanaTokenService(
            IntegrationRepository integrationRepository,
            AsanaOAuthClient asanaOAuthClient,
            AsanaCredentialCodec asanaCredentialCodec,
            AsanaProperties asanaProperties,
            PlatformTransactionManager transactionManager
    ) {
        this(integrationRepository, asanaOAuthClient, asanaCredentialCodec, asanaProperties, transactionManager, Clock.systemUTC());
    }

    AsanaTokenService(
            IntegrationRepository integrationRepository,
            AsanaOAuthClient asanaOAuthClient,
            AsanaCredentialCodec asanaCredentialCodec,
            AsanaProperties asanaProperties,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.integrationRepository = integrationRepository;
        this.asanaOAuthClient = asanaOAuthClient;
        this.asanaCredentialCodec = asanaCredentialCodec;
        this.asanaProperties = asanaProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.revertTransactionTemplate = new TransactionTemplate(transactionManager);
        this.revertTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 캐시된 토큰 재사용 또는 refresh token으로 갱신
    public String getAccessToken(UUID projectId) {
        try {
            return transactionTemplate.execute(status -> refreshIfNeeded(projectId));
        } catch (AsanaRefreshRejectedException exception) {
            // 이 시점엔 위 트랜잭션이 이미 롤백되어 PESSIMISTIC_WRITE 잠금이 풀린 뒤다 —
            // 별도 트랜잭션으로 되돌려도 자기 자신의 잠금과 교착되지 않는다.
            revertTransactionTemplate.executeWithoutResult(status ->
                    integrationRepository.findById(exception.integrationId())
                            .ifPresent(Integration::markPendingSelection));
            throw new UnauthorizedException("Asana refresh token is invalid or revoked.");
        }
    }

    // 토큰 평문이 필요 없는 호출부용
    public void ensureAccessToken(UUID projectId) {
        getAccessToken(projectId);
    }

    private String refreshIfNeeded(UUID projectId) {
        Integration integration = integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.ASANA)
                .orElseThrow(() -> new NotFoundException("Asana integration not found."));

        // 만료 시각이 암호문 안에 있어 잠금 없는 선조회도 복호화가 필요하다 — 짧은 문자열 AES-GCM
        // 1회라 잠금을 피하는 이득이 여전히 크다.
        AsanaCredential credential = asanaCredentialCodec.decrypt(integration.getEncryptedCredential());
        if (isReusable(credential)) {
            return credential.accessToken();
        }

        Integration lockedIntegration = integrationRepository.findByProjectAndProviderForUpdate(projectId, IntegrationProvider.ASANA)
                .orElseThrow(() -> new NotFoundException("Asana integration not found."));
        AsanaCredential lockedCredential = asanaCredentialCodec.decrypt(lockedIntegration.getEncryptedCredential());
        // 잠금 대기 중 다른 트랜잭션이 갱신했을 수 있어 재확인 (double-checked locking)
        if (isReusable(lockedCredential)) {
            return lockedCredential.accessToken();
        }

        AsanaOAuthClient.AsanaTokens refreshed;
        try {
            refreshed = asanaOAuthClient.refresh(lockedCredential.refreshToken());
        } catch (UnauthorizedException exception) {
            // refresh token이 폐기됨(재동의 취소·미사용) — 재발급 수단이 없다. 트랜잭션 콜백은
            // checked 예외를 던질 수 없으므로, 되돌리기 대상 id를 담은 내부 전용 unchecked 예외로 감싸
            // getAccessToken이 트랜잭션 종료 이후 되돌리기를 수행하도록 전달한다.
            throw new AsanaRefreshRejectedException(lockedIntegration.getId());
        }

        // Asana refresh token은 회전하지 않는다 — 갱신 응답에 새 값이 없으므로 기존 값을 보존해야
        // 다음 갱신이 성공한다.
        String refreshToken = refreshed.refreshToken() != null ? refreshed.refreshToken() : lockedCredential.refreshToken();
        AsanaCredential newCredential = new AsanaCredential(
                refreshed.accessToken(),
                refreshToken,
                Instant.now(clock).plusSeconds(refreshed.expiresIn())
        );
        lockedIntegration.updateCredential(asanaCredentialCodec.encrypt(newCredential));
        return newCredential.accessToken();
    }

    // 사용 중 만료를 막기 위해 만료 전 refreshSkew만큼 여유를 두고 갱신 대상으로 처리
    private boolean isReusable(AsanaCredential credential) {
        return credential.expiresAt() != null
                && credential.expiresAt().isAfter(Instant.now(clock).plus(asanaProperties.refreshSkew()));
    }

    private static final class AsanaRefreshRejectedException extends RuntimeException {
        private final UUID integrationId;

        AsanaRefreshRejectedException(UUID integrationId) {
            this.integrationId = integrationId;
        }

        UUID integrationId() {
            return integrationId;
        }
    }
}
