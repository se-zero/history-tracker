package com.history.backend.discord.service;

import java.util.Map;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Discord에는 개별 access token 폐기 API가 없다 — 봇이 길드를 나가는 것이 실질적인 폐기라
// externalRef의 guild_id가 필요하다(A8로 넓힌 시그니처를 실제로 쓰는 provider).
@Service
@RequiredArgsConstructor
public class DiscordCredentialLifecycle implements ProviderCredentialLifecycle {

    private final DiscordClient discordClient;
    private final CredentialCryptoService credentialCryptoService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.DISCORD;
    }

    @Override
    public void revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        String refreshToken = credentialCryptoService.decrypt(encryptedCredential);
        discordClient.revokeToken(refreshToken);

        Object guildId = externalRef.get(DiscordOAuthConnectFlow.GUILD_ID);
        if (guildId instanceof String id && !id.isBlank()) {
            discordClient.leaveGuild(id);
        }
    }
}
