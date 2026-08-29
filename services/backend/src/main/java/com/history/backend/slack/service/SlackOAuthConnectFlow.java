package com.history.backend.slack.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.integration.service.SlackCredential;
import com.history.backend.integration.service.SlackCredentialCodec;
import com.history.backend.slack.SlackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class SlackOAuthConnectFlow implements OAuthConnectFlow {

    // 이 flow가 담아 넣는 external_ref 키 — pipeline-worker가 수집할 때 읽는 키와 같아야 한다
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String WORKSPACE_NAME = "workspace_name";
    // tokens_revoked 매칭 키 — Slack 연동 행에서 사용자를 식별하는 external_ref 필드명
    public static final String CONNECTED_USER_ID = "connected_user_id";
    // BYO(붙여넣기)와 OAuth를 구분한다 — 키가 없으면 OAuth/레거시. 라이프사이클 이벤트에서 BYO 행을 건너뛴다
    public static final String CONNECT_METHOD = "connect_method";
    public static final String CONNECT_METHOD_BYO = "byo";

    private final SlackProperties slackProperties;
    private final SlackClient slackClient;
    private final SlackCredentialCodec codec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.SLACK;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(slackProperties.authorizeUrl())
                .queryParam("client_id", slackProperties.clientId())
                .queryParam("user_scope", slackProperties.userScopes())
                .queryParam("redirect_uri", slackProperties.redirectUri())
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    // Slack은 선택 단계가 없다 — 접근 가능한 전체 채널을 수집하므로 동의만으로 대상이 정해진다.
    @Override
    public OAuthConnection exchangeCode(String code) {
        SlackClient.SlackWorkspace workspace = slackClient.exchangeCode(code);
        return new OAuthConnection(
                codec.serialize(new SlackCredential(workspace.userToken(), workspace.botToken())),
                Map.of(
                        WORKSPACE_ID, workspace.id(),
                        WORKSPACE_NAME, workspace.name(),
                        CONNECTED_USER_ID, workspace.authedUserId()
                )
        );
    }
}
