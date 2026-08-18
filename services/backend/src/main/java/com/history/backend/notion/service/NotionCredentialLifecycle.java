package com.history.backend.notion.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.NotionCredentialCodec;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// externalRef는 쓰지 않는다 — access_token만으로 폐기가 충분하다(남길 봇이 없는 Google Chat과
// 같은 이유. Discord처럼 나가야 할 봇이 없다).
@Service
@RequiredArgsConstructor
public class NotionCredentialLifecycle implements ProviderCredentialLifecycle {

    private final NotionClient notionClient;
    private final NotionCredentialCodec credentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.NOTION;
    }

    @Override
    public void revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        notionClient.revoke(credentialCodec.decrypt(encryptedCredential).accessToken());
    }
}
