package com.history.backend.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.dto.GraphSearchResponse;
import com.history.backend.graph.dto.GraphSubgraphResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AiEngineGraphClient: ai-engine 그래프 HTTP 클라이언트")
class AiEngineGraphClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    @DisplayName("쿼리 파라미터로 스코프된 그래프 개요 조회")
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
    @DisplayName("선택 파라미터 null 시 URL에서 생략")
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
    @DisplayName("ai-engine 오류 시 BadGatewayException 발생")
    void throwsBadGatewayWhenAiEngineFails() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/overview")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchOverview(PROJECT_ID, null, null))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("노드 검색 — q는 URI 변수로 strict 인코딩되어 전달, score 등 추가 필드는 무시")
    void searchesNodesWithStrictlyEncodedQuery() {
        AiEngineGraphClientFixture fixture = fixture();
        // '+' 같은 예약 문자가 그대로 전달되면 서버가 공백으로 해석한다 — %2B 인코딩을 검증
        fixture.server.expect(once(), requestTo(
                        "https://ai-engine.test/graph/search?project_id=" + PROJECT_ID + "&q=a%2Bb&limit=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"nodes":[{"id":"n1","type":"commit","title":"feat: a+b","meta":"abc1234",
                         "source":"github","snippet":"feat: a+b","score":2.7}]}
                        """, MediaType.APPLICATION_JSON));

        GraphSearchResponse result = fixture.client.searchNodes(PROJECT_ID, "a+b", 20);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).id()).isEqualTo("n1");
        assertThat(result.nodes().get(0).type()).isEqualTo("commit");
        fixture.server.verify();
    }

    @Test
    @DisplayName("노드 검색 — limit null 시 생략, 빈 본문 응답은 nodes 빈 배열로 보정")
    void searchOmitsNullLimitAndNormalizesEmptyBody() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://ai-engine.test/graph/search?project_id=" + PROJECT_ID + "&q=auth"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GraphSearchResponse result = fixture.client.searchNodes(PROJECT_ID, "auth", null);

        assertThat(result.nodes()).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("노드 검색 실패 시 BadGatewayException 발생")
    void throwsBadGatewayWhenSearchFails() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/search")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.searchNodes(PROJECT_ID, "auth", null))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("빌드 트리거 — project_id·verify 쿼리로 POST 후 202 상태 본문 반환")
    void triggersBuildWithProjectScopedQueryParams() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/build")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("project_id", PROJECT_ID.toString()))
                .andExpect(queryParam("verify", "true"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .body("""
                                {"state":"running","verify":true,"started_at":"2026-06-24T00:00:00+00:00",
                                 "result":null,"error":null}
                                """)
                        .contentType(MediaType.APPLICATION_JSON));

        GraphBuildStatusResponse result = fixture.client.triggerBuild(PROJECT_ID, true);

        assertThat(result.state()).isEqualTo("running");
        assertThat(result.verify()).isTrue();
        assertThat(result.startedAt()).isEqualTo("2026-06-24T00:00:00+00:00");
        assertThat(result.result()).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("빌드 트리거 실패 시 BadGatewayException 발생")
    void throwsBadGatewayWhenTriggerFails() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/build")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.triggerBuild(PROJECT_ID, false))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("빌드 상태 조회 — project_id 쿼리로 GET, succeeded면 result 포함")
    void fetchesBuildStatus() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/build/status")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("project_id", PROJECT_ID.toString()))
                .andRespond(withSuccess("""
                        {"state":"succeeded","verify":false,"started_at":"2026-06-24T00:00:00+00:00",
                         "result":{"backfilled":1,"triggered_by":2,"discussed_in":3,"reference":4,"thread_propagated":5},
                         "error":null}
                        """, MediaType.APPLICATION_JSON));

        GraphBuildStatusResponse result = fixture.client.fetchBuildStatus(PROJECT_ID);

        assertThat(result.state()).isEqualTo("succeeded");
        assertThat(result.verify()).isFalse();
        assertThat(result.result().triggeredBy()).isEqualTo(2);
        assertThat(result.result().threadPropagated()).isEqualTo(5);
        fixture.server.verify();
    }

    @Test
    @DisplayName("빌드 상태 조회 실패 시 BadGatewayException 발생")
    void throwsBadGatewayWhenStatusFails() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(Matchers.startsWith("https://ai-engine.test/graph/build/status")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchBuildStatus(PROJECT_ID))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("서브그래프 조회 — project_id·evidence 본문으로 POST, nodes/edges/seeds 반환")
    void fetchesSubgraphWithEvidenceBody() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/graph/subgraph"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.evidence[0].type").value("commit"))
                .andExpect(jsonPath("$.evidence[0].id").value("abc1234"))
                .andRespond(withSuccess("""
                        {
                          "nodes": [
                            {"id":"n1","type":"commit","title":"feat: x","meta":"abc1234",
                             "source":"github","snippet":"body"}
                          ],
                          "edges": [["n1","n2"]],
                          "seeds": ["n1", null]
                        }
                        """, MediaType.APPLICATION_JSON));

        GraphSubgraphResponse result =
                fixture.client.fetchSubgraph(PROJECT_ID, List.of(new EvidenceRef("commit", "abc1234")));

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).id()).isEqualTo("n1");
        assertThat(result.edges()).containsExactly(List.of("n1", "n2"));
        // seeds는 evidence 순서 정렬 — 미해석 위치는 null로 유지된다
        assertThat(result.seeds()).containsExactly("n1", null);
        fixture.server.verify();
    }

    @Test
    @DisplayName("서브그래프 조회 — 빈 본문 응답도 nodes/edges/seeds 빈 배열로 보정")
    void normalizesNullSubgraphFields() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/graph/subgraph"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GraphSubgraphResponse result =
                fixture.client.fetchSubgraph(PROJECT_ID, List.of(new EvidenceRef("issue", "HT-1")));

        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
        assertThat(result.seeds()).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("서브그래프 조회 실패 시 BadGatewayException 발생")
    void throwsBadGatewayWhenSubgraphFails() {
        AiEngineGraphClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/graph/subgraph"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchSubgraph(PROJECT_ID, List.of(new EvidenceRef("commit", "abc1234"))))
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
