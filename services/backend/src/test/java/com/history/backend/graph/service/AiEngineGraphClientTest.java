package com.history.backend.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.graph.dto.GraphResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiEngineGraphClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    void fetchesOverviewWithScopedQueryParams() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/overview")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("project_id", PROJECT_ID.toString()))
                .andExpect(queryParam("limit", "50"))
                .andExpect(queryParam("types", "commit"))
                .andRespond(withSuccess("""
                        {
                          "nodes": [
                            {"id":"n1","type":"commit","title":"feat: x","meta":"abc1234",
                             "source":"github","snippet":"body"}
                          ],
                          "edges": [["n1","n2"]]
                        }
                        """, MediaType.APPLICATION_JSON));

        GraphResponse result = fixture.client.fetchOverview(PROJECT_ID, 50, "commit");

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).id()).isEqualTo("n1");
        assertThat(result.nodes().get(0).type()).isEqualTo("commit");
        assertThat(result.edges()).containsExactly(java.util.List.of("n1", "n2"));
        fixture.server.verify();
    }

    @Test
    void omitsOptionalParamsWhenNull() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/graph/overview?project_id=" + PROJECT_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"nodes": [], "edges": []}
                        """, MediaType.APPLICATION_JSON));

        GraphResponse result = fixture.client.fetchOverview(PROJECT_ID, null, null);

        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
        fixture.server.verify();
    }

    @Test
    void throwsBadGatewayWhenAiEngineFails() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/overview")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchOverview(PROJECT_ID, null, null))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private AiEngineGraphClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://ai-engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiEngineGraphClient client = new AiEngineGraphClient(builder.build());
        return new AiEngineGraphClientFixture(client, server);
    }

    private record AiEngineGraphClientFixture(AiEngineGraphClient client, MockRestServiceServer server) {
    }
}
