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

    // externalRef는 필요 없다 — auth.revoke는 토큰만으로 충분하다
    @Override
    public boolean revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        SlackCredential credential = codec.decrypt(encryptedCredential);
        boolean userRevoked = slackClient.revoke(credential.userToken());
        if (credential.botToken() != null && !credential.botToken().isBlank()) {
            // 각 호출을 지역 변수에 담는다 — &&를 호출식에 직접 쓰면 short-circuit으로
            // user 폐기가 false일 때 bot revoke가 아예 호출되지 않아 grant가 남는다.
            boolean botRevoked = slackClient.revoke(credential.botToken());
            return userRevoked && botRevoked;
        }
        return userRevoked;
    }
}
