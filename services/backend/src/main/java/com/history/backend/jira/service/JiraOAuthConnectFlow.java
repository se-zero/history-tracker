package com.history.backend.jira.service;

import java.time.Instant;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.JiraCredential;
import com.history.backend.integration.service.JiraCredentialCodec;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.jira.AtlassianProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class JiraOAuthConnectFlow implements OAuthConnectFlow {

    private final AtlassianProperties atlassianProperties;
    private final JiraOAuthClient jiraOAuthClient;
    private final JiraCredentialCodec jiraCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    // Atlassian은 Slack보다 파라미터가 많다 — audience 고정값, response_type/prompt 지정이 필요하다.
    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(atlassianProperties.authorizeUrl())
                .queryParam("audience", "api.atlassian.com")
                .queryParam("client_id", atlassianProperties.clientId())
                .queryParam("scope", atlassianProperties.scopes())
                .queryParam("redirect_uri", atlassianProperties.redirectUri())
                .queryParam("state", state)
                .queryParam("response_type", "code")
                .queryParam("prompt", "consent")
                .encode()
                .build()
                .toUriString();
    }

    /**
     * Atlassian access token은 1시간 만료라 갱신에 필요한 refresh token·만료 시각까지 함께 담아 저장한다
     * (해석은 {@link JiraCredentialCodec}·{@link JiraTokenService}가 한다).
     *
     * <p>수집 대상인 사이트·프로젝트는 동의 시점에 알 수 없으므로 pending으로 시작한다 —
     * 단계 선언은 {@link JiraSelectionFlow}에 있다.</p>
     */
    @Override
    public OAuthConnection exchangeCode(String code) {
        JiraOAuthClient.JiraTokens tokens = jiraOAuthClient.exchangeCode(code);
        JiraCredential credential = new JiraCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn())
        );
        return OAuthConnection.pendingSelection(jiraCredentialCodec.serialize(credential));
    }
}
