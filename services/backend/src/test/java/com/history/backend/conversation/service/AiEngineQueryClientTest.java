package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiEngineQueryClientTest {

    @Test
    void askPostsQuestionToAiEngine() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"question":"Why did auth change?"}
                        """))
                .andRespond(withSuccess("""
                        {"answer":"OAuth callback was updated."}
                        """, MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?");

        assertThat(result.answer()).isEqualTo("OAuth callback was updated.");
        assertThat(result.fallback()).isFalse();
        fixture.server.verify();
    }

    @Test
    void askReturnsBlankAnswerWhenResponseBodyIsMissing() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?");

        assertThat(result.answer()).isEmpty();
        assertThat(result.fallback()).isFalse();
        fixture.server.verify();
    }

    @Test
    void askReturnsFallbackWhenAiEngineFails() {
        AiEngineQueryClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/query"))
                .andRespond(withServerError());

        AiEngineQueryResult result = fixture.client.ask("Why did auth change?");

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
