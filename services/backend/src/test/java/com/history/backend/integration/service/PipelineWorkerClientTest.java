package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("PipelineWorkerClient: pipeline-worker 수집 트리거 HTTP 클라이언트")
class PipelineWorkerClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    @DisplayName("GitHub 수집 트리거 전송")
    void triggersGitHubCollection() {
        PipelineWorkerClientFixture fixture = fixture();
        expectCollectionTrigger(fixture.server, "github");

        fixture.client.triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID);

        fixture.server.verify();
    }

    @Test
    @DisplayName("Jira 수집 트리거 전송")
    void triggersJiraCollection() {
        PipelineWorkerClientFixture fixture = fixture();
        expectCollectionTrigger(fixture.server, "jira");

        fixture.client.triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);

        fixture.server.verify();
    }

    @Test
    @DisplayName("Slack 수집 트리거 전송")
    void triggersSlackCollection() {
        PipelineWorkerClientFixture fixture = fixture();
        expectCollectionTrigger(fixture.server, "slack");

        fixture.client.triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);

        fixture.server.verify();
    }

    @Test
    @DisplayName("pipeline-worker 오류 시 예외 삼킴")
    void swallowsPipelineWorkerFailure() {
        PipelineWorkerClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://pipeline-worker.test/api/v1/collect/github"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatCode(() -> fixture.client.triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID))
                .doesNotThrowAnyException();
        fixture.server.verify();
    }

    private void expectCollectionTrigger(MockRestServiceServer server, String provider) {
        server.expect(once(), requestTo("https://pipeline-worker.test/api/v1/collect/" + provider))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"projectId":"%s"}
                        """.formatted(PROJECT_ID)))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
    }

    private PipelineWorkerClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://pipeline-worker.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PipelineWorkerClient client = new PipelineWorkerClient(builder.build());
        return new PipelineWorkerClientFixture(client, server);
    }

    private record PipelineWorkerClientFixture(PipelineWorkerClient client, MockRestServiceServer server) {
    }
}
