package com.history.backend.asana.service;

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

    @Override
    public void revoke(byte[] encryptedCredential) {
        asanaOAuthClient.revoke(asanaCredentialCodec.decrypt(encryptedCredential).refreshToken());
    }
}
