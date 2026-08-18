package com.history.backend.notion.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.NotionCredential;
import com.history.backend.integration.service.NotionCredentialCodec;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.notion.NotionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class NotionOAuthConnectFlow implements OAuthConnectFlow {

    // 이 flow가 담아 넣는 external_ref 키. pipeline-worker는 읽지 않는다(수집 범위가 토큰에
    // 암시돼 있다 — Slack과 같다) — 연동 행 표시 이름(IntegrationResponse)에만 쓰인다.
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String WORKSPACE_NAME = "workspace_name";
    public static final String BOT_ID = "bot_id";

    private final NotionProperties properties;
    private final NotionClient notionClient;
    private final NotionCredentialCodec credentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.NOTION;
    }

    // Notion 동의 화면의 페이지 피커가 곧 선택이다 — 사용자가 공유한 페이지만 API로 조회되므로
    // 별도 IntegrationSelectionFlow가 없다.
    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.authorizeUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("response_type", "code")
                .queryParam("owner", "user")
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /**
     * refresh_token은 지금 갱신에 쓰이지 않지만(AccessTokenRefresher 미구현) 자리를 미리 만들어
     * 저장해 둔다. 선택 단계가 없으므로 pending이 아니라 즉시 확정된 연동으로 반환한다.
     */
    @Override
    public OAuthConnection exchangeCode(String code) {
        NotionClient.NotionAuthorization authorization = notionClient.exchangeCode(code);
        NotionCredential credential = new NotionCredential(
                authorization.accessToken(), authorization.refreshToken());
        return new OAuthConnection(
                credentialCodec.serialize(credential),
                Map.of(
                        WORKSPACE_ID, authorization.workspaceId(),
                        WORKSPACE_NAME, authorization.workspaceName(),
                        BOT_ID, authorization.botId()
                )
        );
    }
}
