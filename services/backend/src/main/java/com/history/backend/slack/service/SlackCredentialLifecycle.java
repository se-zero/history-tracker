package com.history.backend.slack.service;

import java.util.Map;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Slack 토큰은 만료되지 않아 갱신 구현이 없다 — 폐기만 담당한다.
@Service
@RequiredArgsConstructor
public class SlackCredentialLifecycle implements ProviderCredentialLifecycle {

    private final SlackClient slackClient;
    private final CredentialCryptoService credentialCryptoService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.SLACK;
    }

    // externalRef는 필요 없다 — auth.revoke는 토큰만으로 충분하다
    @Override
    public void revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        slackClient.revoke(credentialCryptoService.decrypt(encryptedCredential));
    }
}
