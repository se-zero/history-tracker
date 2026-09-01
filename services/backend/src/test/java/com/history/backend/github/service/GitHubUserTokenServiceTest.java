package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.domain.GitHubUserCredentialEntity;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.repository.GitHubUserCredentialRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("GitHubUserTokenService: 사용자 GitHub 토큰 저장·캐시·갱신·폐기")
class GitHubUserTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final Instant NOW = Instant.parse("2026-05-30T03:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String KEY = Base64.getEncoder()
            .encodeToString("test-credential-key-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));
    private static final String REAUTHORIZATION_REQUIRED = "GitHub reauthorization required.";

    // 코덱은 실제 객체 — 만료 시각 계산이 암호화 payload에 들어가는지 복호화로 확인해야 한다
    private final GitHubUserCredentialCodec codec = new GitHubUserCredentialCodec(
            new CredentialCryptoService(new CredentialCryptoProperties(KEY))
    );

    private final GitHubAppProperties gitHubAppProperties = gitHubAppProperties();

    @Mock
    private GitHubUserCredentialRepository gitHubUserCredentialRepository;

    @Mock
    private GitHubUserCredentialCodec gitHubUserCredentialCodec;

    @Mock
    private GitHubOAuthClient gitHubOAuthClient;

    @Test
    @DisplayName("save는 expiresIn·refreshTokenExpiresIn을 clock 기준 Instant로 바꿔 암호화한 뒤 repository에 저장한다")
    void saveEncryptsCredentialWithExpiryInstantsAndPersists() {
        GitHubUserTokenService service = service(codec, new TestTransactionManager());
        GitHubAccessTokenResponse tokens = new GitHubAccessTokenResponse(
                "ghu_access",
                "bearer",
                "",
                "ghr_test",
                28800L,
                15897600L
        );

        service.save(USER_ID, tokens);

        ArgumentCaptor<GitHubUserCredentialEntity> captor = ArgumentCaptor.forClass(GitHubUserCredentialEntity.class);
        verify(gitHubUserCredentialRepository).save(captor.capture());
        GitHubUserCredentialEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getEncryptedCredential()).isNotEmpty();

        GitHubUserCredential decrypted = codec.decrypt(saved.getEncryptedCredential());
        assertThat(decrypted.accessToken()).isEqualTo("ghu_access");
        assertThat(decrypted.refreshToken()).isEqualTo("ghr_test");
        assertThat(decrypted.expiresAt()).isEqualTo(NOW.plusSeconds(28800));
        assertThat(decrypted.refreshTokenExpiresAt()).isEqualTo(NOW.plusSeconds(15897600));
    }

    @Test
    @DisplayName("갱신 유예 시간 이후 만료 시 캐시 토큰 반환 (잠금 조회·refresh 호출 없음)")
    void returnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential()))
                .thenReturn(cachedCredential());

        String result = service.getAccessToken(USER_ID);

        assertThat(result).isEqualTo("cached-access-token");
        verify(gitHubUserCredentialRepository, never()).findByIdForUpdate(USER_ID);
        verify(gitHubOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("갱신 유예 시간 내 만료 시 refresh token으로 갱신하고 회전된 access·refresh·만료 시각을 모두 저장한다")
    void refreshesTokenAndStoresRotatedTokensWhenExpiryIsInsideRefreshSkew() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential()))
                .thenReturn(staleCredential());
        when(gitHubUserCredentialRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubOAuthClient.refresh("old-refresh-token")).thenReturn(refreshedTokens());
        ArgumentCaptor<GitHubUserCredential> credentialCaptor = ArgumentCaptor.forClass(GitHubUserCredential.class);
        when(gitHubUserCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken(USER_ID);

        assertThat(result).isEqualTo("new-access-token");
        assertThat(entity.getEncryptedCredential()).containsExactly(newEncryptedCredential);
        // 회전된 refresh를 저장하지 않으면 다음 갱신이 영구 실패한다
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(28800));
        assertThat(credentialCaptor.getValue().refreshTokenExpiresAt()).isEqualTo(NOW.plusSeconds(15897600));
    }

    @Test
    @DisplayName("잠금 후 재확인 시 다른 트랜잭션이 이미 갱신했으면 그 결과를 재사용한다(교환 호출 안 함)")
    void reusesTokenRefreshedByAnotherTransactionAfterLock() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity staleEntity = credentialEntity(new byte[] {1, 2, 3});
        GitHubUserCredentialEntity refreshedEntity = credentialEntity(new byte[] {4, 5, 6});
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(staleEntity));
        when(gitHubUserCredentialCodec.decrypt(staleEntity.getEncryptedCredential()))
                .thenReturn(staleCredential());
        when(gitHubUserCredentialRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(refreshedEntity));
        when(gitHubUserCredentialCodec.decrypt(refreshedEntity.getEncryptedCredential()))
                .thenReturn(new GitHubUserCredential(
                        "already-refreshed-token",
                        "already-refreshed-refresh",
                        NOW.plusSeconds(3600),
                        NOW.plusSeconds(15897600)
                ));

        String result = service.getAccessToken(USER_ID);

        assertThat(result).isEqualTo("already-refreshed-token");
        verify(gitHubOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("행이 없으면 ForbiddenException — 앱 세션을 끊는 HTTP 401이 아니다")
    void rejectsMissingCredentialWithForbidden() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccessToken(USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(REAUTHORIZATION_REQUIRED);

        verify(gitHubUserCredentialRepository, never()).deleteById(USER_ID);
        verify(gitHubOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("refresh token이 폐기되면 행을 지우고 ForbiddenException을 던진다")
    void deletesRowAndThrowsForbiddenWhenRefreshTokenIsRevoked() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        stubStaleCredentialNeedingRefresh(entity);
        when(gitHubOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new UnauthorizedException("GitHub refresh token is invalid or revoked."));

        assertThatThrownBy(() -> service.getAccessToken(USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(REAUTHORIZATION_REQUIRED);

        verify(gitHubUserCredentialRepository).deleteById(USER_ID);
    }

    @Test
    @DisplayName("refresh 중 GitHub 5xx(BadGatewayException)는 그대로 전파하고 행을 지우지 않는다")
    void propagatesBadGatewayWithoutDeletingRowWhenGitHubHasServerError() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        stubStaleCredentialNeedingRefresh(entity);
        when(gitHubOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new BadGatewayException("GitHub OAuth token refresh request failed."));

        assertThatThrownBy(() -> service.getAccessToken(USER_ID))
                .isInstanceOf(BadGatewayException.class);

        verify(gitHubUserCredentialRepository, never()).deleteById(USER_ID);
    }

    @Test
    @DisplayName("행 삭제는 갱신 실패 트랜잭션이 롤백된 뒤 별도 트랜잭션에서 커밋된다")
    void deletesRowInSeparateTransactionAfterRefreshTransactionRollsBack() {
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, transactionManager);
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        stubStaleCredentialNeedingRefresh(entity);
        when(gitHubOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new UnauthorizedException("GitHub refresh token is invalid or revoked."));

        assertThatThrownBy(() -> service.getAccessToken(USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(REAUTHORIZATION_REQUIRED);

        // 갱신 시도 트랜잭션이 BEGIN·ROLLBACK으로 완전히 끝난 뒤에야 삭제 트랜잭션이 별도로
        // BEGIN·COMMIT된다 — 같은 트랜잭션에서 처리했다면 두 번째 BEGIN/COMMIT이 나타나지 않고,
        // 삭제도 롤백되어 폐기된 refresh 행이 남는다.
        assertThat(transactionManager.events).containsExactly("BEGIN", "ROLLBACK", "BEGIN", "COMMIT");
        verify(gitHubUserCredentialRepository).deleteById(USER_ID);
    }

    @Test
    @DisplayName("바깥에 이미 활성 트랜잭션이 있어도 갱신 트랜잭션은 REQUIRES_NEW로 독립적으로 시작·종료된다")
    void refreshTransactionStartsAndEndsIndependentlyWhenCalledInsideAnOuterTransaction() {
        JoinAwareTransactionManager transactionManager = new JoinAwareTransactionManager();
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, transactionManager);
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential()))
                .thenReturn(cachedCredential());

        org.springframework.transaction.support.TransactionTemplate outerTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        String result = outerTemplate.execute(status -> service.getAccessToken(USER_ID));

        assertThat(result).isEqualTo("cached-access-token");
        // REQUIRED였다면 바깥 트랜잭션에 조용히 합류해 SUSPEND/BEGIN/COMMIT/RESUME 없이
        // [BEGIN, COMMIT] 두 이벤트만 남았을 것이다 — REQUIRES_NEW라 바깥을 잠시 미뤄두고
        // 독립된 트랜잭션을 열고 닫은 뒤 바깥으로 되돌아간다.
        assertThat(transactionManager.events)
                .containsExactly("BEGIN", "SUSPEND", "BEGIN", "COMMIT", "RESUME", "COMMIT");
    }

    @Test
    @DisplayName("행이 없으면 revokeGrant는 true를 반환하고 GitHub을 호출하지 않는다")
    void revokeGrantReturnsTrueWithoutCallingGitHubWhenRowIsMissing() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.empty());

        boolean result = service.revokeGrant(USER_ID);

        assertThat(result).isTrue();
        verify(gitHubOAuthClient, never()).revokeGrant(org.mockito.ArgumentMatchers.anyString());
        verify(gitHubOAuthClient, never()).refresh(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("getAccessToken이 Forbidden이면 grant는 이미 무효이므로 revokeGrant는 true를 반환한다")
    void revokeGrantReturnsTrueWhenAccessTokenRefreshIsUnauthorized() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        stubStaleCredentialNeedingRefresh(entity);
        when(gitHubOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new UnauthorizedException("GitHub refresh token is invalid or revoked."));

        boolean result = service.revokeGrant(USER_ID);

        assertThat(result).isTrue();
        verify(gitHubOAuthClient, never()).revokeGrant(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("getAccessToken이 BadGateway이면 revokeGrant는 false를 반환한다")
    void revokeGrantReturnsFalseWhenAccessTokenRefreshHitsBadGateway() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        stubStaleCredentialNeedingRefresh(entity);
        when(gitHubOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new BadGatewayException("GitHub OAuth token refresh request failed."));

        boolean result = service.revokeGrant(USER_ID);

        assertThat(result).isFalse();
        verify(gitHubOAuthClient, never()).revokeGrant(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("캐시된 access token으로 원격 grant 폐기를 호출하고 그 boolean을 반환한다")
    void revokeGrantDelegatesToOAuthClientWithCachedAccessToken() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential()))
                .thenReturn(cachedCredential());
        when(gitHubOAuthClient.revokeGrant("cached-access-token")).thenReturn(true);

        boolean result = service.revokeGrant(USER_ID);

        assertThat(result).isTrue();
        verify(gitHubOAuthClient).revokeGrant("cached-access-token");
    }

    @Test
    @DisplayName("원격 grant 폐기가 false면 revokeGrant도 false를 반환한다")
    void revokeGrantReturnsFalseWhenOAuthClientRevokeFails() {
        GitHubUserTokenService service = service(gitHubUserCredentialCodec, new TestTransactionManager());
        GitHubUserCredentialEntity entity = credentialEntity(new byte[] {1, 2, 3});
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential()))
                .thenReturn(cachedCredential());
        when(gitHubOAuthClient.revokeGrant("cached-access-token")).thenReturn(false);

        boolean result = service.revokeGrant(USER_ID);

        assertThat(result).isFalse();
        verify(gitHubOAuthClient).revokeGrant("cached-access-token");
    }

    private GitHubUserTokenService service(
            GitHubUserCredentialCodec credentialCodec,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        return new GitHubUserTokenService(
                gitHubUserCredentialRepository,
                credentialCodec,
                gitHubOAuthClient,
                gitHubAppProperties,
                transactionManager,
                CLOCK
        );
    }

    private void stubStaleCredentialNeedingRefresh(GitHubUserCredentialEntity entity) {
        when(gitHubUserCredentialRepository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(gitHubUserCredentialCodec.decrypt(entity.getEncryptedCredential()))
                .thenReturn(staleCredential());
        when(gitHubUserCredentialRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(entity));
    }

    private GitHubUserCredentialEntity credentialEntity(byte[] encryptedCredential) {
        return new GitHubUserCredentialEntity(USER_ID, encryptedCredential);
    }

    private GitHubUserCredential cachedCredential() {
        return new GitHubUserCredential(
                "cached-access-token",
                "cached-refresh-token",
                NOW.plusSeconds(301),
                NOW.plusSeconds(15897600)
        );
    }

    private GitHubUserCredential staleCredential() {
        return new GitHubUserCredential(
                "stale-access-token",
                "old-refresh-token",
                NOW.plusSeconds(200),
                NOW.plusSeconds(15897600)
        );
    }

    private GitHubAccessTokenResponse refreshedTokens() {
        return new GitHubAccessTokenResponse(
                "new-access-token",
                "bearer",
                "",
                "rotated-refresh-token",
                28800L,
                15897600L
        );
    }

    private static GitHubAppProperties gitHubAppProperties() {
        return new GitHubAppProperties(
                "app-id",
                "history-tracker",
                "",
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.test/user",
                "https://api.github.test/user/installations",
                "https://api.github.test/app/installations/{installation_id}/access_tokens",
                "https://api.github.test/installation/repositories",
                "https://api.github.test/repos/{owner}/{repo}/branches",
                "https://api.github.test/user/installations/{installation_id}/repositories",
                "https://api.github.test/users/{username}/installation",
                "https://api.github.test/applications/{client_id}/grant",
                "https://api.github.test/app/installations/{installation_id}",
                REFRESH_SKEW
        );
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
