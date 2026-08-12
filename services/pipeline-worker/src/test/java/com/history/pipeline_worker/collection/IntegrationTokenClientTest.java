package com.history.pipeline_worker.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IntegrationTokenClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    void ensuresTokenWithInternalServiceAuthentication() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "shared-secret"))
                .andRespond(withNoContent());

        assertThat(fixture.client.ensure(PROJECT_ID, CollectionProvider.JIRA))
                .isEqualTo(IntegrationTokenClient.TokenStatus.REFRESHED);
        fixture.server.verify();
    }

    // provider가 경로에 그대로 들어간다 — Jira 전용이던 시절과 달리 어떤 provider든 같은 코드로 호출한다.
    @Test
    void routesProviderIntoPath() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/google-chat/token"
                ))
                .andRespond(withNoContent());

        assertThat(fixture.client.ensure(PROJECT_ID, CollectionProvider.GOOGLE_CHAT))
                .isEqualTo(IntegrationTokenClient.TokenStatus.REFRESHED);
        fixture.server.verify();
    }

    // 501 = "이 provider에는 갱신 수단이 없다"(능력) — 저장된 자격증명 그대로 진행해야 한다.
    @Test
    void returnsNotSupportedWhenBackendHasNoRefresherForProvider() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/slack/token"
                ))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED));

        assertThat(fixture.client.ensure(PROJECT_ID, CollectionProvider.SLACK))
                .isEqualTo(IntegrationTokenClient.TokenStatus.NOT_SUPPORTED);
        fixture.server.verify();
    }

    // 404 = "연동 행이 없다"(리소스) — 해제 직후 레이스다. NOT_SUPPORTED로 읽으면 폐기된 토큰으로
    // 수집을 진행하게 되므로 FAILED로 떨어져 그 provider를 건너뛰어야 한다.
    @Test
    void returnsFailedWhenIntegrationRowIsGone() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(fixture.client.ensure(PROJECT_ID, CollectionProvider.JIRA))
                .isEqualTo(IntegrationTokenClient.TokenStatus.FAILED);
        fixture.server.verify();
    }

    @Test
    void returnsFailedOnBackendErrorInsteadOfThrowing() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(withServerError());

        assertThat(fixture.client.ensure(PROJECT_ID, CollectionProvider.JIRA))
                .isEqualTo(IntegrationTokenClient.TokenStatus.FAILED);
        fixture.server.verify();
    }

    @Test
    void returnsFailedOnNetworkErrorInsteadOfThrowing() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThat(fixture.client.ensure(PROJECT_ID, CollectionProvider.JIRA))
                .isEqualTo(IntegrationTokenClient.TokenStatus.FAILED);
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://backend.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new IntegrationTokenClient(builder.build(), "shared-secret"), server);
    }

    private record Fixture(IntegrationTokenClient client, MockRestServiceServer server) {
    }
}
