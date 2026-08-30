package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.SlackCredential;
import com.history.backend.integration.service.SlackCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlackCredentialLifecycle: 연동 해제 시 user 토큰만 폐기")
class SlackCredentialLifecycleTest {

    private final SlackClient slackClient = mock(SlackClient.class);
    private final SlackCredentialCodec codec = mock(SlackCredentialCodec.class);
    private final SlackCredentialLifecycle lifecycle =
            new SlackCredentialLifecycle(slackClient, codec);

    @Test
    void providerIsSlack() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("user 토큰만 있을 때 폐기 성공 → true 반환 (bot revoke 미호출)")
    void revokeReturnsTrueWhenUserRevokeSucceedsAndBotTokenIsNull() {
        byte[] encrypted = {1, 2, 3};
        when(codec.decrypt(encrypted)).thenReturn(new SlackCredential("xoxp-user", null));
        when(slackClient.revoke("xoxp-user")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isTrue();
        verify(slackClient).revoke("xoxp-user");
        // bot이 없으면 두 번째 revoke가 없어야 한다. anyString()+"bot"은 매처가 깨져
        // never().revoke(anyString())과 같아져 바로 위의 단언과 모순된다.
        verifyNoMoreInteractions(slackClient);
    }

    @Test
    @DisplayName("user 토큰만 있을 때 폐기 실패 → false 반환 (bot revoke 미호출)")
    void revokeReturnsFalseWhenUserRevokeFailsAndBotTokenIsNull() {
        byte[] encrypted = {1, 2, 3};
        when(codec.decrypt(encrypted)).thenReturn(new SlackCredential("xoxp-user", null));
        when(slackClient.revoke("xoxp-user")).thenReturn(false);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isFalse();
        verify(slackClient).revoke("xoxp-user");
    }

    @Test
    @DisplayName("봇 토큰이 있어도 user만 폐기한다 — 봇은 워크스페이스당 하나라 다른 프로젝트가 같은 xoxb를 쓴다")
    void revokeDoesNotRevokeBotTokenEvenWhenPresent() {
        byte[] encrypted = {1, 2, 3};
        when(codec.decrypt(encrypted)).thenReturn(new SlackCredential("xoxp-user", "xoxb-bot"));
        when(slackClient.revoke("xoxp-user")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isTrue();
        verify(slackClient).revoke("xoxp-user");
        verify(slackClient, never()).revoke("xoxb-bot");
    }

    @Test
    @DisplayName("봇 토큰이 있어도 user 폐기 실패면 false이고 bot revoke는 호출하지 않는다")
    void revokeDoesNotRevokeBotTokenWhenUserRevokeFails() {
        byte[] encrypted = {1, 2, 3};
        when(codec.decrypt(encrypted)).thenReturn(new SlackCredential("xoxp-user", "xoxb-bot"));
        when(slackClient.revoke("xoxp-user")).thenReturn(false);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isFalse();
        verify(slackClient).revoke("xoxp-user");
        verify(slackClient, never()).revoke("xoxb-bot");
    }

    @Test
    @DisplayName("connect_method=byo 이면 decrypt/auth.revoke 없이 true — 고객 붙여넣기 토큰을 폐기하지 않는다")
    void revokeReturnsTrueWithoutDecryptOrRemoteRevokeForByoConnection() {
        byte[] encrypted = {1, 2, 3};

        boolean result = lifecycle.revoke(
                encrypted,
                Map.of(SlackOAuthConnectFlow.CONNECT_METHOD, SlackOAuthConnectFlow.CONNECT_METHOD_BYO));

        assertThat(result).isTrue();
        verify(codec, never()).decrypt(any());
        verify(slackClient, never()).revoke(any());
    }

    @Test
    @DisplayName("connect_method 없는 Map.of()는 기존처럼 폐기한다 (OAuth 행 회귀)")
    void revokeStillCallsRemoteRevokeWhenConnectMethodIsAbsent() {
        byte[] encrypted = {1, 2, 3};
        when(codec.decrypt(encrypted)).thenReturn(new SlackCredential("xoxp-user", null));
        when(slackClient.revoke("xoxp-user")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isTrue();
        verify(codec).decrypt(encrypted);
        verify(slackClient).revoke("xoxp-user");
    }
}
