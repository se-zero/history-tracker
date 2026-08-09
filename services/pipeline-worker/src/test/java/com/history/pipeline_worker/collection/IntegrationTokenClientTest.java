package com.history.pipeline_worker.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// JiraTokenClient를 provider 파라미터화한 후신. HTTP 계약(엔드포인트·내부 서비스 인증 헤더·
// 404/오류 매핑)은 JiraTokenClientTest를 그대로 미러하고, 경로가 provider 인자에서 유도됨을
// jira·linear 각각으로 고정한다.
class IntegrationTokenClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    void ensuresTokenWithInternalServiceAuthenticationForJira() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "shared-secret"))
                .andRespond(withNoContent());

        assertThat(fixture.client.ensureToken(PROJECT_ID, CollectionProvider.JIRA)).isTrue();
        fixture.server.verify();
    }

    // 경로가 provider 인자에서 유도됨을 고정 — jira와 다른 provider를 넘기면 다른 경로를 친다.
    @Test
    void ensuresTokenWithInternalServiceAuthenticationForLinear() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/linear/token"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "shared-secret"))
                .andRespond(withNoContent());

        assertThat(fixture.client.ensureToken(PROJECT_ID, CollectionProvider.LINEAR)).isTrue();
        fixture.server.verify();
    }

    @Test
    void returnsFalseWhenBackendRespondsNotFound() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(fixture.client.ensureToken(PROJECT_ID, CollectionProvider.JIRA)).isFalse();
        fixture.server.verify();
    }

    @Test
    void propagatesBackendFailure() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> fixture.client.ensureToken(PROJECT_ID, CollectionProvider.JIRA))
                .isInstanceOf(RestClientResponseException.class);
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
