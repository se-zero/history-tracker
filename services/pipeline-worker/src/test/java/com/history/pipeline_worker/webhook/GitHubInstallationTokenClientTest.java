package com.history.pipeline_worker.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class GitHubInstallationTokenClientTest {

    @Test
    void ensuresInstallationTokenWithInternalServiceAuthentication() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/github/installations/456/token"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "shared-secret"))
                .andRespond(withNoContent());

        assertThat(fixture.client.ensureInstallationToken(456L)).isTrue();
        fixture.server.verify();
    }

    @Test
    void returnsFalseWhenInstallationDoesNotExist() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/github/installations/456/token"
                ))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(fixture.client.ensureInstallationToken(456L)).isFalse();
        fixture.server.verify();
    }

    @Test
    void propagatesBackendFailure() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://backend.test/api/v1/internal/github/installations/456/token"
                ))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> fixture.client.ensureInstallationToken(456L))
                .isInstanceOf(RestClientResponseException.class);
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://backend.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GitHubInstallationTokenClient(builder.build(), "shared-secret"), server);
    }

    private record Fixture(GitHubInstallationTokenClient client, MockRestServiceServer server) {
    }
}
