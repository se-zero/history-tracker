package com.history.backend.asana.service;

import java.time.Instant;

import com.history.backend.asana.AsanaProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AsanaCredential;
import com.history.backend.integration.service.AsanaCredentialCodec;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class AsanaOAuthConnectFlow implements OAuthConnectFlow {

    // Asana 개발자 문서의 고정값 — provider마다 바뀌지 않아 AsanaProperties에 넣지 않는다
    private static final String AUTHORIZE_URL = "https://app.asana.com/-/oauth_authorize";
    private static final String SCOPE = "workspaces:read projects:read tasks:read users:read";

    private final AsanaProperties asanaProperties;
    private final AsanaOAuthClient asanaOAuthClient;
    private final AsanaCredentialCodec asanaCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.ASANA;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", asanaProperties.clientId())
                .queryParam("redirect_uri", asanaProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /**
     * Asana access token은 1시간 만료라 갱신에 필요한 refresh token·만료 시각까지 함께 담아 저장한다
     * (해석은 {@link AsanaCredentialCodec}이 한다).
     *
     * <p>수집 대상인 workspace/project는 동의 시점에 알 수 없으므로 pending으로 시작한다 — 단계 선언은
     * 다음 묶음이다.</p>
     */
    @Override
    public OAuthConnection exchangeCode(String code) {
        AsanaOAuthClient.AsanaTokens tokens = asanaOAuthClient.exchangeCode(code);
        AsanaCredential credential = new AsanaCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn())
        );
        return OAuthConnection.pendingSelection(asanaCredentialCodec.serialize(credential));
    }
}
