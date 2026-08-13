package com.history.backend.linear.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.LinearCredentialCodec;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LinearCredentialLifecycle implements ProviderCredentialLifecycle {

    private final LinearOAuthClient linearOAuthClient;
    private final LinearCredentialCodec linearCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.LINEAR;
    }

    // refresh token을 폐기하면 파생된 access token도 함께 무효화된다 — externalRef는 필요 없다
    // (Discord와 달리 해제 시 별도로 나가야 할 봇이 없다).
    @Override
    public void revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        linearOAuthClient.revoke(linearCredentialCodec.decrypt(encryptedCredential).refreshToken());
    }
}
