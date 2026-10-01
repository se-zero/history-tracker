package com.history.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("McpPromptSpecifications: connect·why 프롬프트 스펙과 결과")
class McpPromptSpecificationsTest {

    private static final String QUESTION = "왜 인증 방식이 바뀌었어?";

    @Test
    @DisplayName("프롬프트 2개를 connect, why 순서로 돌려준다")
    void specificationsReturnsConnectAndWhy() {
        assertThat(specifications()).extracting(spec -> spec.prompt().name()).containsExactly("connect", "why");
        assertThat(specifications()).allSatisfy(spec -> assertThat(spec.prompt().description()).isNotBlank());
    }

    @Test
    @DisplayName("connect는 인자가 없다")
    void connectHasNoArguments() {
        List<McpSchema.PromptArgument> arguments = prompt("connect").arguments();

        assertThat(arguments == null ? List.of() : arguments).isEmpty();
    }

    @Test
    @DisplayName("why는 필수 인자 question 하나를 정의한다")
    void whyDefinesRequiredQuestionArgument() {
        List<McpSchema.PromptArgument> arguments = prompt("why").arguments();

        assertThat(arguments).hasSize(1);
        assertThat(arguments.get(0).name()).isEqualTo("question");
        assertThat(arguments.get(0).required()).isTrue();
        assertThat(arguments.get(0).description()).isNotBlank();
    }

    @Test
    @DisplayName("connect 결과는 list_projects·bind_project로 폴더를 연결하라는 USER 메시지 1개다")
    void connectResultTellsToListAndBindProjects() {
        McpSchema.GetPromptResult result = get("connect", Map.of());

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role()).isEqualTo(McpSchema.Role.USER);
        assertThat(textOf(result)).contains("list_projects").contains("bind_project");
    }

    @Test
    @DisplayName("why 결과는 질문 원문과 explain_commit·ask 사용 지시를 담은 USER 메시지 1개다")
    void whyResultCarriesQuestionAndToolGuidance() {
        McpSchema.GetPromptResult result = get("why", Map.of("question", QUESTION));

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role()).isEqualTo(McpSchema.Role.USER);
        assertThat(textOf(result)).contains(QUESTION).contains("explain_commit").contains("ask");
    }

    @Test
    @DisplayName("why: question이 없어도 예외 없이 결과를 돌려준다")
    void whyWithoutQuestionStillReturnsResult() {
        McpSchema.GetPromptResult result = get("why", Map.of());

        assertThat(result.messages()).hasSize(1);
        assertThat(textOf(result)).isNotBlank();
    }

    @Test
    @DisplayName("why: arguments가 null이어도 예외 없이 결과를 돌려준다")
    void whyWithNullArgumentsStillReturnsResult() {
        McpSchema.GetPromptResult result = get("why", null);

        assertThat(result.messages()).hasSize(1);
        assertThat(textOf(result)).isNotBlank();
    }

    @Test
    @DisplayName("why: question이 공백이어도 예외 없이 결과를 돌려준다")
    void whyWithBlankQuestionStillReturnsResult() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("question", "   ");

        McpSchema.GetPromptResult result = get("why", arguments);

        assertThat(result.messages()).hasSize(1);
        assertThat(textOf(result)).isNotBlank();
    }

    // ── 헬퍼 ──

    private List<SyncPromptSpecification> specifications() {
        return new McpPromptSpecifications().specifications();
    }

    private McpSchema.Prompt prompt(String name) {
        return specifications().stream().map(SyncPromptSpecification::prompt)
                .filter(prompt -> prompt.name().equals(name)).findFirst().orElseThrow();
    }

    private McpSchema.GetPromptResult get(String name, Map<String, Object> arguments) {
        SyncPromptSpecification spec = specifications().stream()
                .filter(s -> s.prompt().name().equals(name)).findFirst().orElseThrow();
        return spec.promptHandler().apply(McpTransportContext.EMPTY, new McpSchema.GetPromptRequest(name, arguments));
    }

    private String textOf(McpSchema.GetPromptResult result) {
        return ((McpSchema.TextContent) result.messages().get(0).content()).text();
    }
}
