package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.AppCredential;
import com.history.backend.integration.repository.AppCredentialRepository;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.service.JiraOAuthClient;
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
@DisplayName("AtlassianAppTokenService: Atlassian 앱 수준 토큰 저장·갱신")
class AtlassianAppTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T03:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private AppCredentialRepository appCredentialRepository;

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
    @DisplayName("동의 code 교환 결과를 암호화해 새 행으로 저장한다 (기존 행 없음)")
    void storeConsentSavesNewRowWhenNoneExists() {
        AtlassianAppTokenService service = service();
        byte[] encryptedCredential = new byte[] {9, 8, 7};
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.empty());
        when(jiraOAuthClient.exchangeCode("consent-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("access-token-1", "refresh-token-1", 3600L));
        ArgumentCaptor<JiraCredential> credentialCaptor = ArgumentCaptor.forClass(JiraCredential.class);
        when(jiraCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(encryptedCredential);
        ArgumentCaptor<AppCredential> appCredentialCaptor = ArgumentCaptor.forClass(AppCredential.class);

        service.storeConsent("consent-code");

        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("access-token-1");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("refresh-token-1");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        verify(appCredentialRepository).save(appCredentialCaptor.capture());
        assertThat(appCredentialCaptor.getValue().getProvider()).isEqualTo(AppCredential.ATLASSIAN);
        assertThat(appCredentialCaptor.getValue().getEncryptedCredential()).containsExactly(encryptedCredential);
    }

    @Test
    @DisplayName("기존 행이 있으면 updateCredential로 교체한다")
    void storeConsentReplacesExistingRowViaUpdateCredential() {
        AtlassianAppTokenService service = service();
        AppCredential existing = AppCredential.atlassian(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {4, 5, 6};
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.of(existing));
        when(jiraOAuthClient.exchangeCode("consent-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("access-token-2", "refresh-token-2", 1800L));
        ArgumentCaptor<JiraCredential> credentialCaptor = ArgumentCaptor.forClass(JiraCredential.class);
        when(jiraCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        service.storeConsent("consent-code");

        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("access-token-2");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("refresh-token-2");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        // 기존 행 객체가 그대로 갱신됐는지 확인 — 새 행을 만들어 저장했다면 이 값은 바뀌지 않는다
        assertThat(existing.getEncryptedCredential()).containsExactly(newEncryptedCredential);
    }

    @Test
    @DisplayName("code가 null이거나 blank면 BadRequestException — exchangeCode 미호출")
    void storeConsentRejectsNullOrBlankCode() {
        AtlassianAppTokenService service = service();

        assertThatThrownBy(() -> service.storeConsent(null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.storeConsent("   ")).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(jiraOAuthClient);
    }

    @Test
    @DisplayName("저장된 행이 없으면 NotFoundException")
    void getAccessTokenRejectsMissingRow() {
        AtlassianAppTokenService service = service();
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccessToken()).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("갱신 유예 시간 이후 만료 시 캐시된 access token을 반환한다 (refresh 미호출, 저장 없음)")
    void getAccessTokenReturnsCachedTokenWhenExpiryIsBeyondRefreshSkew() {
        AtlassianAppTokenService service = service();
        AppCredential existing = AppCredential.atlassian(new byte[] {1, 2, 3});
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.of(existing));
        when(jiraCredentialCodec.decrypt(existing.getEncryptedCredential()))
                .thenReturn(new JiraCredential("cached-access-token", "cached-refresh-token", NOW.plusSeconds(301)));

        String result = service.getAccessToken();

        assertThat(result).isEqualTo("cached-access-token");
        verifyNoInteractions(jiraOAuthClient);
        verify(jiraCredentialCodec, never()).encrypt(any());
    }

    @Test
    @DisplayName("갱신 유예 시간 내 만료 시 refresh token으로 갱신하고 회전된 새 refresh token을 저장한다")
    void getAccessTokenRefreshesAndStoresRotatedRefreshTokenWhenExpiryIsInsideRefreshSkew() {
        AtlassianAppTokenService service = service();
        AppCredential existing = AppCredential.atlassian(new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {9, 8, 7};
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.of(existing));
        when(jiraCredentialCodec.decrypt(existing.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(jiraOAuthClient.refresh("old-refresh-token"))
                .thenReturn(new JiraOAuthClient.JiraTokens("new-access-token", "rotated-refresh-token", 3600L));
        ArgumentCaptor<JiraCredential> credentialCaptor = ArgumentCaptor.forClass(JiraCredential.class);
        when(jiraCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(newEncryptedCredential);

        String result = service.getAccessToken();

        assertThat(result).isEqualTo("new-access-token");
        assertThat(existing.getEncryptedCredential()).containsExactly(newEncryptedCredential);
        // 회전된 새 refresh token을 저장하지 않으면 다음 갱신이 영구 실패한다
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("new-access-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("refresh token이 폐기(401)되면 저장 없이 그대로 전파한다")
    void getAccessTokenPropagatesUnauthorizedWithoutStoringWhenRefreshTokenIsRevoked() {
        AtlassianAppTokenService service = service();
        AppCredential existing = AppCredential.atlassian(new byte[] {1, 2, 3});
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.of(existing));
        when(jiraCredentialCodec.decrypt(existing.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "revoked-refresh-token", NOW.plusSeconds(200)));
        when(jiraOAuthClient.refresh("revoked-refresh-token"))
                .thenThrow(new UnauthorizedException("Jira refresh token is invalid or revoked."));

        assertThatThrownBy(() -> service.getAccessToken()).isInstanceOf(UnauthorizedException.class);

        assertThat(existing.getEncryptedCredential()).containsExactly(1, 2, 3);
        verify(jiraCredentialCodec, never()).encrypt(any());
    }

    @Test
    @DisplayName("refresh 중 Atlassian 5xx(BadGatewayException)는 그대로 전파하고 저장하지 않는다")
    void getAccessTokenPropagatesBadGatewayWithoutStoringWhenAtlassianHasServerError() {
        AtlassianAppTokenService service = service();
        AppCredential existing = AppCredential.atlassian(new byte[] {1, 2, 3});
        when(appCredentialRepository.findById(AppCredential.ATLASSIAN)).thenReturn(Optional.of(existing));
        when(jiraCredentialCodec.decrypt(existing.getEncryptedCredential()))
                .thenReturn(new JiraCredential("stale-access-token", "old-refresh-token", NOW.plusSeconds(200)));
        when(jiraOAuthClient.refresh("old-refresh-token"))
                .thenThrow(new BadGatewayException("Jira OAuth token refresh request failed."));

        assertThatThrownBy(() -> service.getAccessToken()).isInstanceOf(BadGatewayException.class);

        assertThat(existing.getEncryptedCredential()).containsExactly(1, 2, 3);
        verify(jiraCredentialCodec, never()).encrypt(any());
    }

    private AtlassianAppTokenService service() {
        return new AtlassianAppTokenService(
                appCredentialRepository,
                jiraOAuthClient,
                jiraCredentialCodec,
                atlassianProperties,
                new TestTransactionManager(),
                CLOCK
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
}
