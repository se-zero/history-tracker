package com.history.backend.asana.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AsanaCredentialCodec;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsanaCredentialLifecycle implements ProviderCredentialLifecycle {

    private final AsanaOAuthClient asanaOAuthClient;
    private final AsanaCredentialCodec asanaCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.ASANA;
    }

    // refresh token을 폐기하면 파생된 access token도 함께 무효화된다 — externalRef는 필요 없다
    // (Discord와 달리 해제 시 별도로 나가야 할 봇이 없다).
    @Override
    public boolean revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        return asanaOAuthClient.revoke(asanaCredentialCodec.decrypt(encryptedCredential).refreshToken());
    }
}
