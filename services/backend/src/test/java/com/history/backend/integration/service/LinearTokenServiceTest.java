package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.linear.LinearProperties;
import com.history.backend.linear.service.LinearOAuthClient;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

// JiraTokenService와 동일한 알고리즘(캐시 재사용·잠금 후 재확인·회전 저장·되돌리기)을 Linear에 옮긴 것.
@ExtendWith(MockitoExtension.class)
@DisplayName("LinearTokenService: Linear access token 캐시·갱신")
class LinearTokenServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final Instant NOW = Instant.parse("2026-05-30T03:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private LinearOAuthClient linearOAuthClient;

    @Mock
    private LinearCredentialCodec linearCredentialCodec;

    private final LinearProperties linearProperties = new LinearProperties(
            "test-linear-client-id",
            "test-linear-client-secret",
            "https://linear.test/callback",
            "https://api.linear.app/oauth/token",
            "https://api.linear.app/oauth/revoke",
            REFRESH_SKEW
    );

    @Test
    @DisplayName("갱신 유예 시간 이후 만료 시 캐시 토큰 반환 (잠금 조회·refresh 호출 없음)")
    void returnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        LinearTokenService service = service(new TestTransactionManager());
        Integration integration = linearIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(integration));
        when(linearCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new LinearCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(1801)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("cached-access-token");
        verify(integrationRepository, never())
                .findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.LINEAR);
        verify(linearOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("갱신 유예 시간 내 만료 시 refresh token으로 갱신하고 회전된 새 refresh token을 즉시 저장한다")
    void refreshesTokenAndStoresRotatedRefreshTokenWhenExpiryIsInsideRefreshSkew() {
        LinearTokenService service = service(new TestTransactionManager());
        Integration integration = linearIntegration(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(integration));
        when(linearCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new LinearCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(integration));
        when(linearOAuthClient.refresh("old-refresh-token"))
                .thenReturn(new LinearOAuthClient.LinearTokens("new-access-token", "rotated-refresh-token", 86400L));
        ArgumentCaptor<LinearCredential> credentialCaptor = ArgumentCaptor.forClass(LinearCredential.class);
        when(linearCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("new-access-token");
        assertThat(integration.getEncryptedCredential()).containsExactly(newEncryptedCredential);
        // 회전된 새 refresh token을 저장하지 않으면 다음 갱신이 영구 실패한다
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    @DisplayName("잠금 후 재확인 시 다른 트랜잭션이 이미 갱신했으면 그 결과를 재사용한다(교환 호출 안 함)")
    void reusesTokenRefreshedByAnotherTransactionAfterLock() {
        LinearTokenService service = service(new TestTransactionManager());
        Integration staleIntegration = linearIntegration(new byte[] {1, 2, 3});
        Integration refreshedIntegration = linearIntegration(new byte[] {4, 5, 6});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(staleIntegration));
        when(linearCredentialCodec.decrypt(staleIntegration.getEncryptedCredential()))
                .thenReturn(new LinearCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(refreshedIntegration));
        when(linearCredentialCodec.decrypt(refreshedIntegration.getEncryptedCredential()))
                .thenReturn(new LinearCredential("already-refreshed-token", "already-refreshed-refresh", NOW.plusSeconds(86400)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("already-refreshed-token");
        verify(linearOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("refresh token이 폐기(401)되면 연동을 pending으로 되돌리고 UnauthorizedException을 던진다")
    void revertsToPendingAndThrowsWhenRefreshTokenIsRevoked() {
        LinearTokenService service = service(new TestTransactionManager());
        Integration integration = linearIntegration(new byte[] {1, 2, 3});
        UUID integrationId = integration.getId();
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(integration));
        when(linearCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new LinearCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.LINEAR))
                .thenReturn(Optional.of(integration));
        when(linearOAuthClient.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Linear refresh token is invalid or revoked."));
        when(integrationRepository.findById(integrationId)).thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(integration.isPendingSelection()).isTrue();
        // 이미 고른 team은 남아 있어야 재동의 시 자동 복원이 가능하다
        assertThat(integration.externalRefValue("team_id")).isEqualTo("team-1");
    }

    private LinearTokenService service(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new LinearTokenService(
                integrationRepository,
                linearOAuthClient,
                linearCredentialCodec,
                linearProperties,
                transactionManager,
                CLOCK
        );
    }

    private Integration linearIntegration(byte[] encryptedCredential) {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Integration integration = Integration.pendingSelection(project, IntegrationProvider.LINEAR, encryptedCredential);
        integration.applyExternalRef(java.util.Map.of("team_id", "team-1", "team_name", "Engineering"));
        ReflectionTestUtils.setField(integration, "id", UUID.randomUUID());
        return integration;
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
