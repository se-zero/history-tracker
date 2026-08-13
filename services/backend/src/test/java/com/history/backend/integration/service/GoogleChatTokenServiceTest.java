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
import com.history.backend.googlechat.GoogleChatProperties;
import com.history.backend.googlechat.service.GoogleChatClient;
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

// JiraTokenServiceTest와 같은 잠금·트랜잭션 구조를 검증한다(동시 웹훅 처리로 같은 행이 경합할 수 있는
// 상황은 provider와 무관하게 같다). Jira와 다른 지점은 딱 하나 — Google은 갱신 응답에 refresh_token을
// 다시 주지 않으므로(회전하지 않음) "회전된 새 토큰을 저장" 대신 "기존 토큰을 보존"을 검증한다.
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleChatTokenService: Google Chat access token 캐시·갱신")
class GoogleChatTokenServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final Instant NOW = Instant.parse("2026-05-30T03:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private GoogleChatClient client;

    @Mock
    private GoogleChatCredentialCodec credentialCodec;

    private final GoogleChatProperties properties = new GoogleChatProperties(
            "test-client-id",
            "test-client-secret",
            "https://googlechat.test/callback",
            "https://www.googleapis.com/auth/chat.spaces.readonly https://www.googleapis.com/auth/chat.messages.readonly",
            "https://googlechat.test/o/oauth2/v2/auth",
            "https://googlechat.test/token",
            "https://googlechat.test/revoke",
            "https://googlechat.test/v1",
            REFRESH_SKEW
    );

    @Test
    @DisplayName("갱신 유예 시간 이후 만료 시 캐시 토큰 반환 (잠금 조회·refresh 호출 없음)")
    void returnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("cached-access-token");
        verify(integrationRepository, never())
                .findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT);
        verify(client, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("갱신 유예 시간 내 만료 시 refresh token으로 갱신하고, 응답에 refresh_token이 없으면 기존 값을 보존한다")
    void refreshesTokenAndPreservesExistingRefreshTokenWhenResponseOmitsIt() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("stale-access-token", "stable-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        // Google 갱신 응답엔 refresh_token이 없다 — 회전하지 않기 때문
        when(client.refresh("stable-refresh-token"))
                .thenReturn(new GoogleChatClient.GoogleChatTokens("new-access-token", null, 3599L));
        ArgumentCaptor<GoogleChatCredential> credentialCaptor = ArgumentCaptor.forClass(GoogleChatCredential.class);
        when(credentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("new-access-token");
        assertThat(integration.getEncryptedCredential()).containsExactly(newEncryptedCredential);
        // 응답에 없어도 기존 refresh token을 그대로 보존해야 다음 갱신이 가능하다 (Jira와 반대)
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("stable-refresh-token");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(3599));
    }

    @Test
    @DisplayName("잠금 후 재확인 시 다른 트랜잭션이 이미 갱신했으면 그 결과를 재사용한다(교환 호출 안 함)")
    void reusesTokenRefreshedByAnotherTransactionAfterLock() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        Integration staleIntegration = googleChatIntegration(new byte[] {1, 2, 3});
        Integration refreshedIntegration = googleChatIntegration(new byte[] {4, 5, 6});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(staleIntegration));
        when(credentialCodec.decrypt(staleIntegration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("stale-access-token", "stable-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(refreshedIntegration));
        when(credentialCodec.decrypt(refreshedIntegration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("already-refreshed-token", "stable-refresh-token", NOW.plusSeconds(3600)));

        String result = service.getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("already-refreshed-token");
        verify(client, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("연동이 없으면 NotFoundException")
    void rejectsMissingIntegration() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Google Chat integration not found.");
    }

    @Test
    @DisplayName("refresh token이 폐기(401)되면 연동을 pending으로 되돌리고 UnauthorizedException을 던진다")
    void revertsToPendingAndThrowsWhenRefreshTokenIsRevoked() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        UUID integrationId = integration.getId();
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(client.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Google Chat refresh token is invalid or revoked."));
        when(integrationRepository.findById(integrationId)).thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(integration.isPendingSelection()).isTrue();
        // 스페이스 정보는 남아 있어야 재동의 시 자동 복원이 가능하다
        assertThat(integration.externalRefValue("space_id")).isEqualTo("spaces/AAAA");
    }

    @Test
    @DisplayName("refresh 중 Google 5xx(BadGatewayException)는 그대로 전파하고 pending으로 되돌리지 않는다")
    void propagatesBadGatewayWithoutRevertingToPendingWhenGoogleHasServerError() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("stale-access-token", "stable-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(client.refresh("stable-refresh-token"))
                .thenThrow(new BadGatewayException("Google Chat OAuth token refresh request failed."));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(BadGatewayException.class);

        assertThat(integration.isPendingSelection()).isFalse();
        verify(integrationRepository, never()).findById(integration.getId());
    }

    @Test
    @DisplayName("되돌리기는 갱신 실패 트랜잭션이 롤백된 뒤 별도 트랜잭션에서 커밋된다 (자기호출로 무시되지 않음)")
    void revertsToPendingInSeparateTransactionAfterRefreshTransactionRollsBack() {
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        GoogleChatTokenService service = service(transactionManager);
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        UUID integrationId = integration.getId();
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(integrationRepository.findByProjectAndProviderForUpdate(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(client.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Google Chat refresh token is invalid or revoked."));
        when(integrationRepository.findById(integrationId)).thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> service.getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(transactionManager.events).containsExactly("BEGIN", "ROLLBACK", "BEGIN", "COMMIT");
    }

    @Test
    @DisplayName("바깥에 이미 활성 트랜잭션이 있어도 갱신 트랜잭션은 REQUIRES_NEW로 독립적으로 시작·종료된다")
    void refreshTransactionStartsAndEndsIndependentlyWhenCalledInsideAnOuterTransaction() {
        JoinAwareTransactionManager transactionManager = new JoinAwareTransactionManager();
        GoogleChatTokenService service = service(transactionManager);
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        org.springframework.transaction.support.TransactionTemplate outerTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        String result = outerTemplate.execute(status -> service.getAccessToken(PROJECT_ID));

        assertThat(result).isEqualTo("cached-access-token");
        assertThat(transactionManager.events)
                .containsExactly("BEGIN", "SUSPEND", "BEGIN", "COMMIT", "RESUME", "COMMIT");
    }

    @Test
    @DisplayName("ensureAccessToken은 getAccessToken과 동일하게 동작한다(평문 반환 없이)")
    void ensureAccessTokenRefreshesWithoutReturningPlaintext() {
        GoogleChatTokenService service = service(new TestTransactionManager());
        Integration integration = googleChatIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(credentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new GoogleChatCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        service.ensureAccessToken(PROJECT_ID);

        verify(integrationRepository).findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT);
    }

    private GoogleChatTokenService service(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new GoogleChatTokenService(
                integrationRepository,
                client,
                credentialCodec,
                properties,
                transactionManager,
                CLOCK
        );
    }

    private Integration googleChatIntegration(byte[] encryptedCredential) {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Integration integration = Integration.pendingSelection(project, IntegrationProvider.GOOGLE_CHAT, encryptedCredential);
        integration.applyExternalRef(java.util.Map.of("space_id", "spaces/AAAA", "space_name", "engineering"));
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
            return Boolean.TRUE;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            events.add("RESUME");
            transactionOpen = true;
        }
    }
}
