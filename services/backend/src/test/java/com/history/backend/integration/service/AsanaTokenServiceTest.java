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

import com.history.backend.asana.AsanaProperties;
import com.history.backend.asana.service.AsanaOAuthClient;
import com.history.backend.auth.domain.User;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
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

// LinearTokenService와 동일한 알고리즘(캐시 재사용·잠금 후 재확인·되돌리기)을 Asana에 옮긴 것.
// 단, Asana refresh token은 회전하지 않는다 — 갱신 응답에 refresh_token이 없으면 기존 값을
// 보존해 저장해야 한다(Linear식으로 응답 값을 무조건 저장하면 다음 갱신이 영구 실패한다).
@ExtendWith(MockitoExtension.class)
@DisplayName("AsanaTokenService: Asana access token 캐시·갱신")
class AsanaTokenServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final Instant NOW = Instant.parse("2026-05-30T03:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private AsanaOAuthClient asanaOAuthClient;

    @Mock
    private AsanaCredentialCodec asanaCredentialCodec;

    private final AsanaProperties asanaProperties = new AsanaProperties(
            "test-asana-client-id",
            "test-asana-client-secret",
            "https://asana.test/callback",
            "https://app.asana.com/-/oauth_token",
            "https://app.asana.com/-/oauth_revoke",
            REFRESH_SKEW
    );

    @Test
    @DisplayName("갱신 유예 시간 이후 만료 시 캐시 토큰 반환 (잠금 조회·refresh 호출 없음)")
    void returnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        AsanaTokenService service = service(new TestTransactionManager());
        Integration integration = asanaIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new AsanaCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(1801)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("cached-access-token");
        verify(integrationRepository, never())
                .findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.ASANA);
        verify(asanaOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("갱신 유예 시간 내 만료 시 refresh token으로 갱신하고, 응답에 새 refresh token이 있으면 그 값으로 교체 저장한다")
    void refreshesTokenAndStoresNewRefreshTokenWhenRefreshResponseIncludesIt() {
        AsanaTokenService service = service(new TestTransactionManager());
        Integration integration = asanaIntegration(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new AsanaCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaOAuthClient.refresh("old-refresh-token"))
                .thenReturn(new AsanaOAuthClient.AsanaTokens("new-access-token", "unexpected-rotated-refresh-token", 3600L));
        ArgumentCaptor<AsanaCredential> credentialCaptor = ArgumentCaptor.forClass(AsanaCredential.class);
        when(asanaCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("new-access-token");
        assertThat(integration.getEncryptedCredential()).containsExactly(newEncryptedCredential);
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("unexpected-rotated-refresh-token");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("갱신 응답에 refresh token이 없으면(Asana는 회전하지 않는다) 기존 refresh token을 그대로 보존해 저장한다")
    void refreshPreservesExistingRefreshTokenWhenRefreshResponseOmitsIt() {
        AsanaTokenService service = service(new TestTransactionManager());
        Integration integration = asanaIntegration(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new AsanaCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaOAuthClient.refresh("old-refresh-token"))
                .thenReturn(new AsanaOAuthClient.AsanaTokens("new-access-token", null, 3600L));
        ArgumentCaptor<AsanaCredential> credentialCaptor = ArgumentCaptor.forClass(AsanaCredential.class);
        when(asanaCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("new-access-token");
        // Linear식(응답 값을 무조건 저장)으로 구현되면 여기서 null이 저장돼 실패해야 한다.
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("old-refresh-token");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("잠금 후 재확인 시 다른 트랜잭션이 이미 갱신했으면 그 결과를 재사용한다(교환 호출 안 함)")
    void reusesTokenRefreshedByAnotherTransactionAfterLock() {
        AsanaTokenService service = service(new TestTransactionManager());
        Integration staleIntegration = asanaIntegration(new byte[] {1, 2, 3});
        Integration refreshedIntegration = asanaIntegration(new byte[] {4, 5, 6});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(staleIntegration));
        when(asanaCredentialCodec.decrypt(staleIntegration.getEncryptedCredential()))
                .thenReturn(new AsanaCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(refreshedIntegration));
        when(asanaCredentialCodec.decrypt(refreshedIntegration.getEncryptedCredential()))
                .thenReturn(new AsanaCredential("already-refreshed-token", "already-refreshed-refresh", NOW.plusSeconds(3600)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("already-refreshed-token");
        verify(asanaOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("refresh token이 폐기(401류)되면 연동을 pending으로 되돌리고 UnauthorizedException을 던진다")
    void revertsToPendingAndThrowsWhenRefreshTokenIsRevoked() {
        AsanaTokenService service = service(new TestTransactionManager());
        Integration integration = asanaIntegration(new byte[] {1, 2, 3});
        UUID integrationId = integration.getId();
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new AsanaCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.ASANA))
                .thenReturn(Optional.of(integration));
        when(asanaOAuthClient.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Asana refresh token is invalid or revoked."));
        when(integrationRepository.findById(integrationId)).thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(integration.isPendingSelection()).isTrue();
        // 이미 고른 workspace/project는 남아 있어야 재동의 시 자동 복원이 가능하다
        assertThat(integration.externalRefValue("workspace_gid")).isEqualTo("workspace-1");
        assertThat(integration.externalRefValue("project_gid")).isEqualTo("project-1");
    }

    private AsanaTokenService service(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new AsanaTokenService(
                integrationRepository,
                asanaOAuthClient,
                asanaCredentialCodec,
                asanaProperties,
                transactionManager,
                CLOCK
        );
    }

    private Integration asanaIntegration(byte[] encryptedCredential) {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Integration integration = Integration.pendingSelection(project, IntegrationProvider.ASANA, encryptedCredential);
        integration.applyExternalRef(java.util.Map.of(
                "workspace_gid", "workspace-1",
                "workspace_name", "Acme",
                "project_gid", "project-1",
                "project_name", "Roadmap"
        ));
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
