package com.history.backend.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.integration.domain.IntegrationProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiscordCredentialLifecycle: 연동 해제 시 OAuth grant 폐기 + 봇 길드 퇴장")
class DiscordCredentialLifecycleTest {

    private final DiscordClient discordClient = mock(DiscordClient.class);
    private final CredentialCryptoService credentialCryptoService = mock(CredentialCryptoService.class);
    private final DiscordCredentialLifecycle lifecycle =
            new DiscordCredentialLifecycle(discordClient, credentialCryptoService);

    @Test
    void providerIsDiscord() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.DISCORD);
    }

    @Test
    @DisplayName("guild_id가 있으면 refresh token 폐기와 봇 길드 퇴장을 모두 호출한다")
    void revokeCallsTokenRevokeAndGuildLeave() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("refresh-token");

        lifecycle.revoke(encrypted, Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1"));

        verify(discordClient).revokeToken("refresh-token");
        verify(discordClient).leaveGuild("G1");
    }

    @Test
    @DisplayName("guild_id가 없으면 봇 길드 퇴장은 건너뛴다 — 자격증명만으로도 grant 폐기는 해야 한다")
    void revokeSkipsGuildLeaveWhenGuildIdMissing() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("refresh-token");

        lifecycle.revoke(encrypted, Map.of());

        verify(discordClient).revokeToken("refresh-token");
        verify(discordClient, never()).leaveGuild(anyString());
    }
}
