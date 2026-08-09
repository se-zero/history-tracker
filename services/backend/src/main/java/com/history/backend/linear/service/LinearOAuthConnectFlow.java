package com.history.backend.linear.service;

import java.time.Instant;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.LinearCredential;
import com.history.backend.integration.service.LinearCredentialCodec;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.linear.LinearProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class LinearOAuthConnectFlow implements OAuthConnectFlow {

    // Linear 개발자 문서의 고정값 — provider마다 바뀌지 않아 LinearProperties에 넣지 않는다
    private static final String AUTHORIZE_URL = "https://linear.app/oauth/authorize";
    private static final String SCOPE = "read";

    private final LinearProperties linearProperties;
    private final LinearOAuthClient linearOAuthClient;
    private final LinearCredentialCodec linearCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.LINEAR;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", linearProperties.clientId())
                .queryParam("redirect_uri", linearProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /**
     * Linear access token은 24시간 만료라 갱신에 필요한 refresh token·만료 시각까지 함께 담아 저장한다
     * (해석은 {@link LinearCredentialCodec}이 한다).
     *
     * <p>수집 대상인 team은 동의 시점에 알 수 없으므로 pending으로 시작한다 — 단계 선언은 다음 묶음이다.</p>
     */
    @Override
    public OAuthConnection exchangeCode(String code) {
        LinearOAuthClient.LinearTokens tokens = linearOAuthClient.exchangeCode(code);
        LinearCredential credential = new LinearCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn())
        );
        return OAuthConnection.pendingSelection(linearCredentialCodec.serialize(credential));
    }
}
