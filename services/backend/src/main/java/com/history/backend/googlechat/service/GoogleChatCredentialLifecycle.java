package com.history.backend.googlechat.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.GoogleChatCredentialCodec;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleChatCredentialLifecycle implements ProviderCredentialLifecycle {

    private final GoogleChatClient client;
    private final GoogleChatCredentialCodec credentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.GOOGLE_CHAT;
    }

    // refresh token(grant)을 폐기하면 파생된 access token도 함께 무효화된다 — externalRef는 필요
    // 없다(Discord와 달리 해제 시 별도로 나가야 할 봇이 없다).
    @Override
    public void revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        client.revoke(credentialCodec.decrypt(encryptedCredential).refreshToken());
    }
}
