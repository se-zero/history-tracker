package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.conversation.dto.AiEngineHistoryMessage;
import com.history.backend.conversation.dto.AiEnginePriorEvidence;
import com.history.backend.conversation.service.AiEngineQueryResult.FallbackReason;
import com.history.backend.graph.dto.EvidenceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AiEngineQueryClient: ai-engine 질의 HTTP 클라이언트")
class AiEngineQueryClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    @DisplayName("대화 전체 컨텍스트를 ai-engine에 POST")
    void askPostsFullConversationContextToAiEngine() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "question":"Why did auth change?",
                          "project_id":"f4dfc513-bb7b-41f4-aaf9-46bcc18380f8",
                          "history":[
                            {"role":"user","content":"What changed?"},
                            {"role":"assistant","content":"PR #18 changed auth."}
                          ],
                          "prior_evidence":[
                            {"type":"pull_request","id":"#18","quote":"OAuth callback update"}
                          ],
                          "running_summary":{
                            "summary":"Earlier discussion covered HT-37.",
                            "entities":[{"type":"issue","id":"HT-37"}],
                            "unresolved_aspects":["deployment impact"]
                          },
                          "focus_evidence":[{"type":"commit","id":"abc1234def"}]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "answer":"OAuth callback was updated.",
                          "structured":{
                            "summary":"OAuth callback was updated.",
                            "evidence":[],
                            "unknown_aspects":[]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ), List.of(new AiEnginePriorEvidence("pull_request", "#18", "OAuth callback update")), Map.of(
                "summary", "Earlier discussion covered HT-37.",
                "entities", List.of(Map.of("type", "issue", "id", "HT-37")),
                "unresolved_aspects", List.of("deployment impact")
        ), List.of(new EvidenceRef("commit", "abc1234def")));

        assertThat(result.answer()).isEqualTo("OAuth callback was updated.");
        assertThat(result.fallback()).isFalse();
        assertThat(result.structured()).isEqualTo(Map.of(
                "summary", "OAuth callback was updated.",
                "evidence", List.of(),
                "unknown_aspects", List.of()
        ));
        fixture.server.verify();
    }

    @Test
    @DisplayName("응답 본문 없을 때 fallback 반환")
    void askReturnsFallbackWhenResponseBodyIsMissing() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.answer()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.fallback()).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("answer 공백일 때 fallback 반환")
    void askReturnsFallbackWhenAnswerIsBlank() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("""
                        {"answer":"  "}
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.answer()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.fallback()).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("ai-engine 오류 시 fallback 반환")
    void askReturnsFallbackWhenAiEngineFails() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withServerError());

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.answer()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.fallback()).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("성공하면 실패 사유는 NONE")
    void askSuccessHasNoneReason() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("""
                        {"answer":"OAuth callback was updated."}
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.fallback()).isFalse();
        assertThat(result.reason()).isEqualTo(FallbackReason.NONE);
        fixture.server.verify();
    }

    @Test
    @DisplayName("읽기 시간 초과는 TIMEOUT 사유의 fallback")
    void askReturnsTimeoutReasonWhenReadTimesOut() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.fallback()).isTrue();
        assertThat(result.reason()).isEqualTo(FallbackReason.TIMEOUT);
        fixture.server.verify();
    }

    // 연결·읽기 시간 초과는 JDK에서 같은 예외 타입이고 메시지만 다르다. 연결 단계(수 초)에서 끊긴 것을
    // TIMEOUT으로 분류하면 "read timeout 초 안에 답을 못 만들었다"는 안내가 사실과 달라진다.
    @Test
    @DisplayName("연결 시간 초과는 TIMEOUT이 아니라 ERROR 사유의 fallback")
    void askReturnsErrorReasonWhenConnectTimesOut() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Connect timed out");
                });

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.fallback()).isTrue();
        assertThat(result.reason()).isEqualTo(FallbackReason.ERROR);
        fixture.server.verify();
    }

    @Test
    @DisplayName("서버 오류는 ERROR 사유의 fallback")
    void askReturnsErrorReasonWhenAiEngineFails() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withServerError());

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.fallback()).isTrue();
        assertThat(result.reason()).isEqualTo(FallbackReason.ERROR);
        fixture.server.verify();
    }

    @Test
    @DisplayName("빈 답은 ERROR 사유의 fallback")
    void askReturnsErrorReasonWhenAnswerIsBlank() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("""
                        {"answer":"  "}
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of());

        assertThat(result.fallback()).isTrue();
        assertThat(result.reason()).isEqualTo(FallbackReason.ERROR);
        fixture.server.verify();
    }

    @Test
    @DisplayName("기존 요약과 이전 히스토리를 ai-engine에 POST하여 누적 요약 갱신")
    void summarizePostsExistingSummaryAndOldHistory() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query/summary"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "running_summary":{
                            "summary":"existing",
                            "entities":[],
                            "unresolved_aspects":[]
                          },
                          "history":[
                            {"role":"user","content":"Old question"},
                            {"role":"assistant","content":"Old answer"}
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "summary":{
                            "summary":"merged",
                            "entities":[],
                            "unresolved_aspects":[]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = fixture.client.summarize(
                Map.of(
                        "summary", "existing",
                        "entities", List.of(),
                        "unresolved_aspects", List.of()
                ),
                List.of(
                        new AiEngineHistoryMessage("user", "Old question"),
                        new AiEngineHistoryMessage("assistant", "Old answer")
                )
        );

        assertThat(result).containsEntry("summary", "merged");
        fixture.server.verify();
    }

    private AiEngineQueryClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://ai-engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiEngineQueryClient client = new AiEngineQueryClient(builder.build());
        return new AiEngineQueryClientFixture(client, server);
    }

    private record AiEngineQueryClientFixture(AiEngineQueryClient client, MockRestServiceServer server) {
    }
}
