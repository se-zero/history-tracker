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

class JiraTokenClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    void ensuresJiraTokenWithInternalServiceAuthentication() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "shared-secret"))
                .andRespond(withNoContent());

        assertThat(fixture.client.ensureJiraToken(PROJECT_ID)).isTrue();
        fixture.server.verify();
    }

    @Test
    void returnsFalseWhenIntegrationDoesNotExist() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(fixture.client.ensureJiraToken(PROJECT_ID)).isFalse();
        fixture.server.verify();
    }

    @Test
    void propagatesBackendFailure() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/integrations/" + PROJECT_ID + "/jira/token"
                ))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> fixture.client.ensureJiraToken(PROJECT_ID))
                .isInstanceOf(RestClientResponseException.class);
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://backend.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new JiraTokenClient(builder.build(), "shared-secret"), server);
    }

    private record Fixture(JiraTokenClient client, MockRestServiceServer server) {
    }
}
