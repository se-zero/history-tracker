package com.history.backend.integration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.service.JiraOAuthClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// Jira access token 캐싱 갱신 — GitHub InstallationTokenService의 알고리즘을 옮긴 것.
// GitHub과 다른 점 둘: 만료 시각이 암호화된 credential JSON 안에 있어 잠금 없는 선조회도 복호화가 필요하고,
// refresh token이 매 갱신마다 회전해 새 값을 반드시 저장해야 다음 갱신이 성공한다.
@Service
public class JiraTokenService {

    private final IntegrationRepository integrationRepository;
    private final JiraOAuthClient jiraOAuthClient;
    private final JiraCredentialCodec jiraCredentialCodec;
    private final AtlassianProperties atlassianProperties;
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
    public JiraTokenService(
            IntegrationRepository integrationRepository,
            JiraOAuthClient jiraOAuthClient,
            JiraCredentialCodec jiraCredentialCodec,
            AtlassianProperties atlassianProperties,
            PlatformTransactionManager transactionManager
    ) {
        this(integrationRepository, jiraOAuthClient, jiraCredentialCodec, atlassianProperties, transactionManager, Clock.systemUTC());
    }

    JiraTokenService(
            IntegrationRepository integrationRepository,
            JiraOAuthClient jiraOAuthClient,
            JiraCredentialCodec jiraCredentialCodec,
            AtlassianProperties atlassianProperties,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.integrationRepository = integrationRepository;
        this.jiraOAuthClient = jiraOAuthClient;
        this.jiraCredentialCodec = jiraCredentialCodec;
        this.atlassianProperties = atlassianProperties;
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
        } catch (JiraRefreshRejectedException exception) {
            // 이 시점엔 위 트랜잭션이 이미 롤백되어 PESSIMISTIC_WRITE 잠금이 풀린 뒤다 —
            // 별도 트랜잭션으로 되돌려도 자기 자신의 잠금과 교착되지 않는다.
            revertTransactionTemplate.executeWithoutResult(status ->
                    integrationRepository.findById(exception.integrationId())
                            .ifPresent(Integration::markPendingSelection));
            throw new UnauthorizedException("Jira refresh token is invalid or revoked.");
        }
    }

    // 토큰 평문이 필요 없는 호출부용
    public void ensureAccessToken(UUID projectId) {
        getAccessToken(projectId);
    }

    private String refreshIfNeeded(UUID projectId) {
        Integration integration = integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.JIRA)
                .orElseThrow(() -> new NotFoundException("Jira integration not found."));

        // 만료 시각이 암호문 안에 있어 잠금 없는 선조회도 복호화가 필요하다 — 짧은 문자열 AES-GCM
        // 1회라 잠금을 피하는 이득이 여전히 크다.
        JiraCredential credential = jiraCredentialCodec.decrypt(integration.getEncryptedCredential());
        if (isReusable(credential)) {
            return credential.accessToken();
        }

        Integration lockedIntegration = integrationRepository.findByProjectAndProviderForUpdate(projectId, IntegrationProvider.JIRA)
                .orElseThrow(() -> new NotFoundException("Jira integration not found."));
        JiraCredential lockedCredential = jiraCredentialCodec.decrypt(lockedIntegration.getEncryptedCredential());
        // 잠금 대기 중 다른 트랜잭션이 갱신했을 수 있어 재확인 (double-checked locking)
        if (isReusable(lockedCredential)) {
            return lockedCredential.accessToken();
        }

        JiraOAuthClient.JiraTokens refreshed;
        try {
            refreshed = jiraOAuthClient.refresh(lockedCredential.refreshToken());
        } catch (UnauthorizedException exception) {
            // refresh token이 폐기됨(재동의 취소·90일 미사용) — 재발급 수단이 없다. 트랜잭션 콜백은
            // checked 예외를 던질 수 없으므로, 되돌리기 대상 id를 담은 내부 전용 unchecked 예외로 감싸
            // getAccessToken이 트랜잭션 종료 이후 되돌리기를 수행하도록 전달한다.
            throw new JiraRefreshRejectedException(lockedIntegration.getId());
        }

        JiraCredential newCredential = new JiraCredential(
                refreshed.accessToken(),
                refreshed.refreshToken(),
                Instant.now(clock).plusSeconds(refreshed.expiresIn())
        );
        lockedIntegration.updateCredential(jiraCredentialCodec.encrypt(newCredential));
        return newCredential.accessToken();
    }

    // 사용 중 만료를 막기 위해 만료 전 refreshSkew만큼 여유를 두고 갱신 대상으로 처리
    private boolean isReusable(JiraCredential credential) {
        return credential.expiresAt() != null
                && credential.expiresAt().isAfter(Instant.now(clock).plus(atlassianProperties.refreshSkew()));
    }

    private static final class JiraRefreshRejectedException extends RuntimeException {
        private final UUID integrationId;

        JiraRefreshRejectedException(UUID integrationId) {
            this.integrationId = integrationId;
        }

        UUID integrationId() {
            return integrationId;
        }
    }
}
