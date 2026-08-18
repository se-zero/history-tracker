package com.history.backend.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.graph.dto.ActorAliasDetail;
import com.history.backend.graph.dto.ActorDecisionListResponse;
import com.history.backend.graph.dto.ActorDecisionResponse;
import com.history.backend.graph.dto.ActorDetailResponse;
import com.history.backend.graph.dto.ActorListResponse;
import com.history.backend.graph.dto.ActorMergeResponse;
import com.history.backend.graph.dto.ActorResponse;
import com.history.backend.graph.dto.ActorSourceName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AiEngineActorClient: ai-engine Actor 관리 HTTP 클라이언트")
class AiEngineActorClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    @DisplayName("액터 목록 조회 — source_names를 sourceNames(source/name/erased)로, activity_count를 activityCount로 매핑")
    void fetchesActorsWithSourceNames() {
        AiEngineActorClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/graph/actors?project_id=" + PROJECT_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"actors": [{"uuid": "a1", "name": "Younghee Kim", "activity_count": 42,
                          "source_names": [{"source": "JIRA", "name": "김영희", "erased": null},
                                            {"source": "GITHUB", "name": "Younghee Kim", "erased": null}]}]}
                        """, MediaType.APPLICATION_JSON));

        ActorListResponse result = fixture.client.fetchActors(PROJECT_ID);

        assertThat(result.actors()).hasSize(1);
        ActorResponse actor = result.actors().get(0);
        assertThat(actor.uuid()).isEqualTo("a1");
        assertThat(actor.name()).isEqualTo("Younghee Kim");
        assertThat(actor.activityCount()).isEqualTo(42L);
        assertThat(actor.sourceNames()).hasSize(2);
        ActorSourceName jiraName = actor.sourceNames().get(0);
        assertThat(jiraName.source()).isEqualTo("JIRA");
        assertThat(jiraName.name()).isEqualTo("김영희");
        assertThat(jiraName.erased()).isNull();
        assertThat(actor.sourceNames().get(1).source()).isEqualTo("GITHUB");
        fixture.server.verify();
    }

    @Test
    @DisplayName("액터 상세 조회 — aliases를 sourceId/source/name/email/erased로 매핑")
    void fetchesActorDetailWithAliases() {
        AiEngineActorClientFixture fixture = fixture();
        fixture.server.expect(once(),
                        requestTo("https://ai-engine.test/graph/actors/a1?project_id=" + PROJECT_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"uuid": "a1", "name": "Younghee Kim",
                         "aliases": [{"source_id": "JIRA:5b10a2", "source": "JIRA", "name": "김영희",
                                      "email": "yh@corp.com", "erased": null}]}
                        """, MediaType.APPLICATION_JSON));

        ActorDetailResponse result = fixture.client.fetchActorDetail(PROJECT_ID, "a1");

        assertThat(result.uuid()).isEqualTo("a1");
        assertThat(result.name()).isEqualTo("Younghee Kim");
        assertThat(result.aliases()).hasSize(1);
        ActorAliasDetail alias = result.aliases().get(0);
        assertThat(alias.sourceId()).isEqualTo("JIRA:5b10a2");
        assertThat(alias.source()).isEqualTo("JIRA");
        assertThat(alias.name()).isEqualTo("김영희");
        assertThat(alias.email()).isEqualTo("yh@corp.com");
        assertThat(alias.erased()).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("액터 상세 조회 — 404 시 detail 사유를 유지한 채 NotFoundException 발생")
    void throwsNotFoundWithDetailWhenActorMissing() {
        AiEngineActorClientFixture fixture = fixture();
        fixture.server.expect(once(),
                        requestTo("https://ai-engine.test/graph/actors/x?project_id=" + PROJECT_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"detail\": \"Actor 없음: x\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchActorDetail(PROJECT_ID, "x"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Actor 없음: x");
        fixture.server.verify();
    }

    @Test
    @DisplayName("액터 병합 — project_id/uuid_a/uuid_b/note 본문으로 POST (name은 보내지 않는다)")
    void mergesActorsWithNewRequestBody() {
        AiEngineActorClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/actors/merge"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"project_id": "%s", "uuid_a": "a1", "uuid_b": "a2", "note": "같은 사람"}
                        """.formatted(PROJECT_ID), JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"decision_id": "d1", "canonical_uuid": "a1", "merged_uuid": "a2",
                         "moved_edges": 3, "aliases": [], "distinct_removed": 0}
                        """, MediaType.APPLICATION_JSON));

        ActorMergeResponse result = fixture.client.mergeActors(PROJECT_ID, "a1", "a2", "같은 사람");

        assertThat(result.decisionId()).isEqualTo("d1");
        assertThat(result.canonicalUuid()).isEqualTo("a1");
        assertThat(result.mergedUuid()).isEqualTo("a2");
        fixture.server.verify();
    }

    @Test
    @DisplayName("액터 결정 목록 조회 — aliases_a/aliases_b를 ActorSourceName 목록으로 매핑")
    void fetchesDecisionsWithSourceNameAliases() {
        AiEngineActorClientFixture fixture = fixture();
        fixture.server.expect(once(),
                        requestTo("https://ai-engine.test/actors/decisions?project_id=" + PROJECT_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"decisions": [{"decision_id": "d1", "kind": "same",
                          "aliases_a": [{"source": "JIRA", "name": "김영희", "erased": null}],
                          "aliases_b": [{"source": "GITHUB", "name": "Younghee Kim", "erased": null}],
                          "canonical_uuid": "t", "merged_uuid": "a2", "note": "", "decided_at": "2026-08-02T00:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        ActorDecisionListResponse result = fixture.client.fetchDecisions(PROJECT_ID);

        assertThat(result.decisions()).hasSize(1);
        ActorDecisionResponse decision = result.decisions().get(0);
        assertThat(decision.decisionId()).isEqualTo("d1");
        assertThat(decision.kind()).isEqualTo("same");
        assertThat(decision.canonicalUuid()).isEqualTo("t");
        assertThat(decision.decidedAt()).isEqualTo("2026-08-02T00:00:00Z");
        assertThat(decision.aliasesA()).hasSize(1);
        ActorSourceName aliasA = decision.aliasesA().get(0);
        assertThat(aliasA.source()).isEqualTo("JIRA");
        assertThat(aliasA.name()).isEqualTo("김영희");
        assertThat(aliasA.erased()).isNull();
        assertThat(decision.aliasesB()).hasSize(1);
        assertThat(decision.aliasesB().get(0).source()).isEqualTo("GITHUB");
        fixture.server.verify();
    }

    private AiEngineActorClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://ai-engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiEngineActorClient client = new AiEngineActorClient(builder.build());
        return new AiEngineActorClientFixture(client, server);
    }

    private record AiEngineActorClientFixture(AiEngineActorClient client, MockRestServiceServer server) {
    }
}
