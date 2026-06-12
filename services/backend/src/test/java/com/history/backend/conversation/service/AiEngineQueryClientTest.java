package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;

import com.history.backend.conversation.dto.AiEngineHistoryMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiEngineQueryClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    void askPostsQuestionToAiEngine() {
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
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"answer":"OAuth callback was updated."}
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ));

        assertThat(result.answer()).isEqualTo("OAuth callback was updated.");
        assertThat(result.fallback()).isFalse();
        fixture.server.verify();
    }

    @Test
    void askReturnsFallbackWhenResponseBodyIsMissing() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of());

        assertThat(result.answer()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.fallback()).isTrue();
        fixture.server.verify();
    }

    @Test
    void askReturnsFallbackWhenAnswerIsBlank() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("""
                        {"answer":"  "}
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of());

        assertThat(result.answer()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.fallback()).isTrue();
        fixture.server.verify();
    }

    @Test
    void askReturnsFallbackWhenAiEngineFails() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withServerError());

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?", PROJECT_ID, List.of());

        assertThat(result.answer()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.fallback()).isTrue();
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
