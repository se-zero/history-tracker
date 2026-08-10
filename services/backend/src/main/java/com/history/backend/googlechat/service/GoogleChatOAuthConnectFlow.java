package com.history.backend.googlechat.service;

import java.time.Instant;

import com.history.backend.googlechat.GoogleChatProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.GoogleChatCredential;
import com.history.backend.integration.service.GoogleChatCredentialCodec;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class GoogleChatOAuthConnectFlow implements OAuthConnectFlow {

    private final GoogleChatProperties properties;
    private final GoogleChatClient client;
    private final GoogleChatCredentialCodec credentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.GOOGLE_CHAT;
    }

    // access_type=offline + prompt=consent가 없으면 refresh_token 없이 응답한다 — 매 갱신마다
    // 재동의를 요구하게 되므로 반드시 둘 다 넣는다.
    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.authorizeUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", properties.scopes())
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /**
     * Google Chat access token은 1시간 만료라 갱신에 필요한 refresh token·만료 시각까지 함께 담아
     * 저장한다(해석은 {@link GoogleChatCredentialCodec}·{@code GoogleChatTokenService}가 한다).
     *
     * <p>수집 대상인 스페이스는 동의 시점에 알 수 없으므로 pending으로 시작한다 — 단계 선언은
     * {@link GoogleChatSelectionFlow}에 있다.</p>
     */
    @Override
    public OAuthConnection exchangeCode(String code) {
        GoogleChatClient.GoogleChatTokens tokens = client.exchangeCode(code);
        GoogleChatCredential credential = new GoogleChatCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn())
        );
        return OAuthConnection.pendingSelection(credentialCodec.serialize(credential));
    }
}
