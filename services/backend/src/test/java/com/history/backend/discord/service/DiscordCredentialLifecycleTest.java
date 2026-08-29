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
    @DisplayName("guild_id가 있고 둘 다 성공하면 refresh token 폐기와 봇 길드 퇴장을 모두 호출하고 true를 반환한다")
    void revokeCallsTokenRevokeAndGuildLeaveAndReturnsTrueWhenBothSucceed() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("refresh-token");
        when(discordClient.revokeToken("refresh-token")).thenReturn(true);
        when(discordClient.leaveGuild("G1")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1"));

        assertThat(result).isTrue();
        verify(discordClient).revokeToken("refresh-token");
        verify(discordClient).leaveGuild("G1");
    }

    @Test
    @DisplayName("guild_id가 없으면 봇 길드 퇴장은 건너뛰고, 토큰 폐기 결과를 그대로 반환한다")
    void revokeSkipsGuildLeaveWhenGuildIdMissing() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("refresh-token");
        when(discordClient.revokeToken("refresh-token")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isTrue();
        verify(discordClient).revokeToken("refresh-token");
        verify(discordClient, never()).leaveGuild(anyString());
    }

    @Test
    @DisplayName("토큰 폐기만 실패해도 봇 길드 퇴장은 여전히 호출되고, 전체 결과는 false다 "
            + "(&&를 호출식에 직접 쓰면 short-circuit으로 두 번째 호출이 아예 안 나가는 버그를 잡는다)")
    void revokeStillCallsLeaveGuildWhenOnlyTokenRevokeFailsAndReturnsFalse() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("refresh-token");
        when(discordClient.revokeToken("refresh-token")).thenReturn(false);
        when(discordClient.leaveGuild("G1")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1"));

        assertThat(result).isFalse();
        verify(discordClient).revokeToken("refresh-token");
        verify(discordClient).leaveGuild("G1");
    }

    @Test
    @DisplayName("봇 길드 퇴장만 실패해도 토큰 폐기는 이미 호출된 상태이며, 전체 결과는 false다")
    void revokeStillCallsTokenRevokeWhenOnlyLeaveGuildFailsAndReturnsFalse() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("refresh-token");
        when(discordClient.revokeToken("refresh-token")).thenReturn(true);
        when(discordClient.leaveGuild("G1")).thenReturn(false);

        boolean result = lifecycle.revoke(encrypted, Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1"));

        assertThat(result).isFalse();
        verify(discordClient).revokeToken("refresh-token");
        verify(discordClient).leaveGuild("G1");
    }
}
