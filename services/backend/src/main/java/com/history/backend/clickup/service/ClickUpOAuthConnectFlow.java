package com.history.backend.clickup.service;

import com.history.backend.clickup.ClickUpProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.ClickUpCredential;
import com.history.backend.integration.service.ClickUpCredentialCodec;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class ClickUpOAuthConnectFlow implements OAuthConnectFlow {

    // ClickUp 개발자 문서의 고정값 — provider마다 바뀌지 않아 ClickUpProperties에 넣지 않는다.
    // scope·response_type 개념이 없다.
    private static final String AUTHORIZE_URL = "https://app.clickup.com/api";

    private final ClickUpProperties clickUpProperties;
    private final ClickUpOAuthClient clickUpOAuthClient;
    private final ClickUpCredentialCodec clickUpCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.CLICKUP;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", clickUpProperties.clientId())
                .queryParam("redirect_uri", clickUpProperties.redirectUri())
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /**
     * ClickUp access token은 만료·회전이 없어 access token만 담아 저장한다
     * (해석은 {@link ClickUpCredentialCodec}이 한다).
     *
     * <p>수집 대상인 workspace/space/folder/list는 동의 시점에 알 수 없으므로 pending으로 시작한다 —
     * 단계 선언은 {@link ClickUpSelectionFlow}가 맡는다.</p>
     */
    @Override
    public OAuthConnection exchangeCode(String code) {
        ClickUpOAuthClient.ClickUpTokens tokens = clickUpOAuthClient.exchangeCode(code);
        ClickUpCredential credential = new ClickUpCredential(tokens.accessToken());
        return OAuthConnection.pendingSelection(clickUpCredentialCodec.serialize(credential));
    }
}
