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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.service.JiraOAuthClient;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraTokenService: Jira access token 캐시·갱신")
class JiraTokenServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final Instant NOW = Instant.parse("2026-05-30T03:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private JiraOAuthClient jiraOAuthClient;

    @Mock
    private JiraCredentialCodec jiraCredentialCodec;

    private final AtlassianProperties atlassianProperties = new AtlassianProperties(
            "test-client-id",
            "test-client-secret",
            "https://atlassian.test/callback",
            "read:jira-work read:jira-user offline_access",
            "https://atlassian.test/authorize",
            "https://atlassian.test/oauth/token",
            "https://atlassian.test/oauth/token/accessible-resources",
            "https://atlassian.test/oauth/revoke",
            "https://atlassian.test/ex/jira",
            REFRESH_SKEW
    );

    @Test
    @DisplayName("갱신 유예 시간 이후 만료 시 캐시 토큰 반환 (잠금 조회·refresh 호출 없음)")
    void returnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        JiraTokenService service = service(new TestTransactionManager());
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("cached-access-token");
        verify(integrationRepository, never())
                .findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.JIRA);
        verify(jiraOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("갱신 유예 시간 내 만료 시 refresh token으로 갱신하고 회전된 새 refresh token을 저장한다")
    void refreshesTokenAndStoresRotatedRefreshTokenWhenExpiryIsInsideRefreshSkew() {
        JiraTokenService service = service(new TestTransactionManager());
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraOAuthClient.refresh("old-refresh-token"))
                .thenReturn(new JiraOAuthClient.JiraTokens("new-access-token", "rotated-refresh-token", 3600L));
        ArgumentCaptor<JiraCredential> credentialCaptor = ArgumentCaptor.forClass(JiraCredential.class);
        when(jiraCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("new-access-token");
        assertThat(integration.getEncryptedCredential()).containsExactly(newEncryptedCredential);
        // 회전된 새 refresh token을 저장하지 않으면 다음 갱신이 영구 실패한다
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("잠금 후 재확인 시 다른 트랜잭션이 이미 갱신했으면 그 결과를 재사용한다(교환 호출 안 함)")
    void reusesTokenRefreshedByAnotherTransactionAfterLock() {
        JiraTokenService service = service(new TestTransactionManager());
        Integration staleIntegration = jiraIntegration(new byte[] {1, 2, 3});
        Integration refreshedIntegration = jiraIntegration(new byte[] {4, 5, 6});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(staleIntegration));
        when(jiraCredentialCodec.decrypt(staleIntegration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(refreshedIntegration));
        when(jiraCredentialCodec.decrypt(refreshedIntegration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("already-refreshed-token", "already-refreshed-refresh", NOW.plusSeconds(3600)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("already-refreshed-token");
        verify(jiraOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("연동이 없으면 NotFoundException")
    void rejectsMissingIntegration() {
        JiraTokenService service = service(new TestTransactionManager());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Jira integration not found.");
    }

    @Test
    @DisplayName("refresh token이 폐기(401)되면 연동을 pending으로 되돌리고 UnauthorizedException을 던진다")
    void revertsToPendingAndThrowsWhenRefreshTokenIsRevoked() {
        JiraTokenService service = service(new TestTransactionManager());
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        UUID integrationId = integration.getId();
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraOAuthClient.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Jira refresh token is invalid or revoked."));
        when(integrationRepository.findById(integrationId)).thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(integration.isPendingSelection()).isTrue();
        // 사이트·프로젝트 정보는 남아 있어야 재동의 시 자동 복원이 가능하다
        assertThat(integration.selectionValue("cloud_id")).isEqualTo("cloud-1");
        assertThat(integration.selectionValue("project_key")).isEqualTo("PROJ");
    }

    @Test
    @DisplayName("refresh 중 Atlassian 5xx(BadGatewayException)는 그대로 전파하고 pending으로 되돌리지 않는다")
    void propagatesBadGatewayWithoutRevertingToPendingWhenAtlassianHasServerError() {
        JiraTokenService service = service(new TestTransactionManager());
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new BadGatewayException("Jira OAuth token refresh request failed."));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(BadGatewayException.class);

        assertThat(integration.isPendingSelection()).isFalse();
        verify(integrationRepository, never()).findById(integration.getId());
    }

    @Test
    @DisplayName("되돌리기는 갱신 실패 트랜잭션이 롤백된 뒤 별도 트랜잭션에서 커밋된다 (자기호출로 무시되지 않음)")
    void revertsToPendingInSeparateTransactionAfterRefreshTransactionRollsBack() {
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        JiraTokenService service = service(transactionManager);
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        UUID integrationId = integration.getId();
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraOAuthClient.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Jira refresh token is invalid or revoked."));
        when(integrationRepository.findById(integrationId)).thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);

        // 갱신 시도 트랜잭션이 BEGIN·ROLLBACK으로 완전히 끝난 뒤에야 되돌리기 트랜잭션이 별도로
        // BEGIN·COMMIT된다 — 같은 트랜잭션에서 처리했다면(함정) 두 번째 BEGIN/COMMIT이 나타나지 않는다.
        assertThat(transactionManager.events).containsExactly("BEGIN", "ROLLBACK", "BEGIN", "COMMIT");
    }

    @Test
    @DisplayName("바깥에 이미 활성 트랜잭션이 있어도 갱신 트랜잭션은 REQUIRES_NEW로 독립적으로 시작·종료된다")
    void refreshTransactionStartsAndEndsIndependentlyWhenCalledInsideAnOuterTransaction() {
        JoinAwareTransactionManager transactionManager = new JoinAwareTransactionManager();
        JiraTokenService service = service(transactionManager);
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        // 호출부가 이미 트랜잭션을 열어 둔 상태를 재현 — 나중에 누군가 getAccessToken을 @Transactional
        // 안에서 부르는 상황과 동일하다.
        org.springframework.transaction.support.TransactionTemplate outerTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        String result = outerTemplate.execute(status -> service.getAccessToken(PROJECT_ID));

        assertThat(result).isEqualTo("cached-access-token");
        // REQUIRED였다면 바깥 트랜잭션에 조용히 합류해 SUSPEND/BEGIN/COMMIT/RESUME 없이
        // [BEGIN, COMMIT] 두 이벤트만 남았을 것이다 — REQUIRES_NEW라 바깥을 잠시 미뤄두고
        // 독립된 트랜잭션을 열고 닫은 뒤 바깥으로 되돌아간다.
        assertThat(transactionManager.events)
                .containsExactly("BEGIN", "SUSPEND", "BEGIN", "COMMIT", "RESUME", "COMMIT");
    }

    @Test
    @DisplayName("ensureAccessToken은 getAccessToken과 동일하게 동작한다(평문 반환 없이)")
    void ensureAccessTokenRefreshesWithoutReturningPlaintext() {
        JiraTokenService service = service(new TestTransactionManager());
        Integration integration = jiraIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new JiraCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        service.ensureAccessToken(PROJECT_ID);

        verify(integrationRepository).findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA);
    }

    private JiraTokenService service(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new JiraTokenService(
                integrationRepository,
                jiraOAuthClient,
                jiraCredentialCodec,
                atlassianProperties,
                transactionManager,
                CLOCK
        );
    }

    private Integration jiraIntegration(byte[] encryptedCredential) {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Integration integration = Integration.pendingSelection(project, IntegrationProvider.JIRA, encryptedCredential);
        integration.applySelections(java.util.Map.of(
                "cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
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

    private static class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> events = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("BEGIN");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("COMMIT");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("ROLLBACK");
        }
    }

    // TrackingTransactionManager와 달리 "이미 진행 중인 트랜잭션이 있는가"를 실제로 추적한다 —
    // REQUIRED는 이 상태를 보고 조용히 합류(BEGIN/COMMIT 없음)하고, REQUIRES_NEW는 SUSPEND 후
    // 별도로 BEGIN/COMMIT하고 RESUME한다. 단순 TrackingTransactionManager는 이 차이를 구분하지
    // 못해(항상 "기존 트랜잭션 없음") REQUIRED로 되돌려도 테스트가 거짓으로 통과해 버린다.
    private static class JoinAwareTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> events = new ArrayList<>();
        private boolean transactionOpen = false;

        private static final class TransactionHandle {
            private final boolean existing;

            private TransactionHandle(boolean existing) {
                this.existing = existing;
            }
        }

        @Override
        protected Object doGetTransaction() {
            return new TransactionHandle(transactionOpen);
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TransactionHandle) transaction).existing;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("BEGIN");
            transactionOpen = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("COMMIT");
            transactionOpen = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("ROLLBACK");
            transactionOpen = false;
        }

        @Override
        protected Object doSuspend(Object transaction) {
            events.add("SUSPEND");
            transactionOpen = false;
            // Spring의 resume(...)은 doSuspend가 null을 반환하면 "되돌릴 리소스가 없다"고 보고
            // doResume을 아예 호출하지 않는다 — RESUME 이벤트를 남기려면 non-null 마커가 필요하다.
            return Boolean.TRUE;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            events.add("RESUME");
            transactionOpen = true;
        }
    }
}
