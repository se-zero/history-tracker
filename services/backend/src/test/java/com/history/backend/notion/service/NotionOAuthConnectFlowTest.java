package com.history.backend.notion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.NotionCredential;
import com.history.backend.integration.service.NotionCredentialCodec;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.notion.NotionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotionOAuthConnectFlow: Notion 동의 URL 조립·code 교환")
class NotionOAuthConnectFlowTest {

    private final NotionProperties notionProperties = new NotionProperties(
            "test-client-id",
            "test-client-secret",
            "https://notion.test/callback",
            "https://notion.test/v1/oauth/authorize",
            "https://notion.test/v1/oauth/token",
            "https://notion.test/v1/oauth/revoke",
            "https://notion.test/v1",
            "2026-03-11"
    );

    private final NotionClient notionClient = mock(NotionClient.class);
    // 실제 AES-GCM 암복호화를 태워 credential 문자열이 유효한 JSON인지까지 검증한다(codec을 목으로
    // 대체하면 exchangeCode가 실제로 무엇을 직렬화하는지 놓친다).
    private final NotionCredentialCodec credentialCodec = new NotionCredentialCodec(
            new CredentialCryptoService(new CredentialCryptoProperties(
                    java.util.Base64.getEncoder().encodeToString(
                            "test-credential-key-32-bytes!!!!".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    private final NotionOAuthConnectFlow flow =
            new NotionOAuthConnectFlow(notionProperties, notionClient, credentialCodec);

    @Test
    void providerIsNotion() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.NOTION);
    }

    @Test
    @DisplayName("동의 URL은 client_id·response_type=code·owner=user·redirect_uri·state를 담는다")
    void buildAuthorizeUrlAssemblesNotionParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://notion.test/v1/oauth/authorize"
                        + "?client_id=test-client-id"
                        + "&response_type=code"
                        + "&owner=user"
                        + "&redirect_uri=https://notion.test/callback"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 access/refresh token을 담은 JSON credential과 워크스페이스 참조 — 선택 단계가 없어 즉시 확정된다")
    void exchangeCodeReturnsConfirmedConnectionWithWorkspaceReference() {
        when(notionClient.exchangeCode("auth-code")).thenReturn(new NotionClient.NotionAuthorization(
                "access-token", "refresh-token", "W1", "Acme", "bot-1"));

        OAuthConnection connection = flow.exchangeCode("auth-code");

        // credential은 암호화 전 평문(JSON 직렬화 결과)이다 — IntegrationService가 저장 시 암호화한다.
        assertThat(connection.credential())
                .isEqualTo(credentialCodec.serialize(new NotionCredential("access-token", "refresh-token")));
        assertThat(connection.externalRef()).containsOnly(
                Map.entry(NotionOAuthConnectFlow.WORKSPACE_ID, "W1"),
                Map.entry(NotionOAuthConnectFlow.WORKSPACE_NAME, "Acme"),
                Map.entry(NotionOAuthConnectFlow.BOT_ID, "bot-1"));
        // 선택 단계가 없으므로 pending이 아니라 즉시 확정된 연동이다.
        assertThat(connection.externalRef()).isNotEmpty();
    }
}
