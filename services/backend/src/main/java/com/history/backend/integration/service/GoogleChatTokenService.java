package com.history.backend.integration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.googlechat.GoogleChatProperties;
import com.history.backend.googlechat.service.GoogleChatClient;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Google Chat access token 캐싱 갱신 — {@code JiraTokenService}의 잠금·트랜잭션 구조를 그대로
 * 옮긴 것이다(동시 웹훅 처리로 같은 행이 경합할 수 있는 상황은 provider와 무관하게 같다).
 *
 * <p>Jira와 다른 점 하나: Google은 갱신 응답에 refresh_token을 다시 주지 않는다(회전하지 않음) —
 * 그래서 새 credential을 만들 때 갱신 응답에 값이 없으면 기존 refresh token을 그대로 보존한다.
 * (Jira는 반대로 회전하는 refresh token을 반드시 덮어써야 다음 갱신이 성공한다.)</p>
 */
@Service
public class GoogleChatTokenService {

    private final IntegrationRepository integrationRepository;
    private final GoogleChatClient client;
    private final GoogleChatCredentialCodec credentialCodec;
    private final GoogleChatProperties properties;
    private final Clock clock;
    // REQUIRES_NEW로 고정하는 이유는 JiraTokenService와 동일하다 — 호출 문맥(내부 토큰 API 단독 호출 vs
    // 다른 트랜잭션 안에서의 self-invocation)과 무관하게 항상 독립적으로 시작·종료되게 한다.
    private final TransactionTemplate transactionTemplate;
    // 갱신 실패 시 pending 되돌리기는 실패한 갱신 트랜잭션과 절대 겹치면 안 된다 — PESSIMISTIC_WRITE
    // 행 잠금이 살아있는 채로 같은 행을 갱신하려 들면 자기 자신과 교착된다.
    private final TransactionTemplate revertTransactionTemplate;

    @Autowired
    public GoogleChatTokenService(
            IntegrationRepository integrationRepository,
            GoogleChatClient client,
            GoogleChatCredentialCodec credentialCodec,
            GoogleChatProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this(integrationRepository, client, credentialCodec, properties, transactionManager, Clock.systemUTC());
    }

    GoogleChatTokenService(
            IntegrationRepository integrationRepository,
            GoogleChatClient client,
            GoogleChatCredentialCodec credentialCodec,
            GoogleChatProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.integrationRepository = integrationRepository;
        this.client = client;
        this.credentialCodec = credentialCodec;
        this.properties = properties;
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
        } catch (GoogleChatRefreshRejectedException exception) {
            // 이 시점엔 위 트랜잭션이 이미 롤백되어 PESSIMISTIC_WRITE 잠금이 풀린 뒤다 —
            // 별도 트랜잭션으로 되돌려도 자기 자신의 잠금과 교착되지 않는다.
            revertTransactionTemplate.executeWithoutResult(status ->
                    integrationRepository.findById(exception.integrationId())
                            .ifPresent(Integration::markPendingSelection));
            throw new UnauthorizedException("Google Chat refresh token is invalid or revoked.");
        }
    }

    // 토큰 평문이 필요 없는 호출부용
    public void ensureAccessToken(UUID projectId) {
        getAccessToken(projectId);
    }

    private String refreshIfNeeded(UUID projectId) {
        Integration integration = integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.GOOGLE_CHAT)
                .orElseThrow(() -> new NotFoundException("Google Chat integration not found."));

        GoogleChatCredential credential = credentialCodec.decrypt(integration.getEncryptedCredential());
        if (isReusable(credential)) {
            return credential.accessToken();
        }

        Integration lockedIntegration = integrationRepository.findByProjectAndProviderForUpdate(projectId, IntegrationProvider.GOOGLE_CHAT)
                .orElseThrow(() -> new NotFoundException("Google Chat integration not found."));
        GoogleChatCredential lockedCredential = credentialCodec.decrypt(lockedIntegration.getEncryptedCredential());
        // 잠금 대기 중 다른 트랜잭션이 갱신했을 수 있어 재확인 (double-checked locking)
        if (isReusable(lockedCredential)) {
            return lockedCredential.accessToken();
        }

        GoogleChatClient.GoogleChatTokens refreshed;
        try {
            refreshed = client.refresh(lockedCredential.refreshToken());
        } catch (UnauthorizedException exception) {
            // refresh token이 폐기됨(사용자가 access 취소·6개월 미사용·Testing 상태 7일 만료) — 재발급
            // 수단이 없다. 트랜잭션 콜백은 checked 예외를 던질 수 없으므로, 되돌리기 대상 id를 담은
            // 내부 전용 unchecked 예외로 감싸 getAccessToken이 트랜잭션 종료 이후 되돌리기를 수행하도록
            // 전달한다.
            throw new GoogleChatRefreshRejectedException(lockedIntegration.getId());
        }

        // Google은 갱신 응답에 refresh_token을 다시 주지 않는다 — null이면 기존 값을 그대로 보존한다.
        String refreshToken = refreshed.refreshToken() != null ? refreshed.refreshToken() : lockedCredential.refreshToken();
        GoogleChatCredential newCredential = new GoogleChatCredential(
                refreshed.accessToken(),
                refreshToken,
                Instant.now(clock).plusSeconds(refreshed.expiresIn())
        );
        lockedIntegration.updateCredential(credentialCodec.encrypt(newCredential));
        return newCredential.accessToken();
    }

    // 사용 중 만료를 막기 위해 만료 전 refreshSkew만큼 여유를 두고 갱신 대상으로 처리
    private boolean isReusable(GoogleChatCredential credential) {
        return credential.expiresAt() != null
                && credential.expiresAt().isAfter(Instant.now(clock).plus(properties.refreshSkew()));
    }

    private static final class GoogleChatRefreshRejectedException extends RuntimeException {
        private final UUID integrationId;

        GoogleChatRefreshRejectedException(UUID integrationId) {
            this.integrationId = integrationId;
        }

        UUID integrationId() {
            return integrationId;
        }
    }
}
