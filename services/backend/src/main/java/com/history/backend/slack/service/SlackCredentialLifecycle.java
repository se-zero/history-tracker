package com.history.backend.slack.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import com.history.backend.integration.service.SlackCredential;
import com.history.backend.integration.service.SlackCredentialCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Slack 토큰은 만료되지 않아 갱신 구현이 없다 — 폐기만 담당한다.
@Service
@RequiredArgsConstructor
public class SlackCredentialLifecycle implements ProviderCredentialLifecycle {

    private final SlackClient slackClient;
    private final SlackCredentialCodec codec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.SLACK;
    }

    // BYO가 아니면 auth.revoke는 토큰만으로 충분하다 — BYO는 고객 소유라 위에서 이미 return
    @Override
    public boolean revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        // BYO 붙여넣기 토큰은 고객 소유라 우리 앱이 auth.revoke 하면 안 된다
        if (externalRef != null
                && SlackOAuthConnectFlow.CONNECT_METHOD_BYO.equals(
                        externalRef.get(SlackOAuthConnectFlow.CONNECT_METHOD))) {
            return true;
        }
        SlackCredential credential = codec.decrypt(encryptedCredential);
        // 봇 토큰은 워크스페이스당 하나다. 여기서 auth.revoke 하면 같은 워크스페이스를 연결한
        // 다른 프로젝트의 /why-code까지 끊긴다. 앱 제거는 워크스페이스 관리자 → app_uninstalled.
        return slackClient.revoke(credential.userToken());
    }
}
