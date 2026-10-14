package com.history.backend.mcp.service;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

// 에이전트 UI에서 사용자가 직접 고르는 슬래시 프롬프트(connect·why). 도구 호출 순서를 문장으로 안내할 뿐 로직은 없다.
// 프롬프트 본문은 에이전트가 읽는 지시라 영어 한 벌이다. 프롬프트 설명은 사용자도 목록에서 보므로 한·영 병기다.
@Component
public class McpPromptSpecifications {

    private static final String CONNECT_TEXT = "Connect the current working folder to a whycode project. "
            + "Get the project list with list_projects and let the user choose which project it is (confirm even if there is only one), "
            + "then call bind_project with the absolute path of the current working folder as workspace.";

    private static final String WHY_TEXT = "Answer the following question with the whycode tools. "
            + "For a specific line or snippet, get the commit hash with git blame and call explain_commit "
            + "with language set to the question's language (\"ko\" or \"en\"); otherwise call ask with the question "
            + "in the user's language. workspace is the absolute path of the current working folder. "
            + "Reply to the user in the language of the question.\n\nQuestion: ";

    public List<SyncPromptSpecification> specifications() {
        McpSchema.Prompt connect = new McpSchema.Prompt("connect",
                "현재 작업 폴더를 whycode 프로젝트에 연결합니다 / Connect the current working folder to a whycode project",
                List.of());
        McpSchema.Prompt why = new McpSchema.Prompt("why",
                "코드가 왜 이렇게 바뀌었는지 whycode에 묻습니다 / Ask whycode why code changed",
                List.of(new McpSchema.PromptArgument("question", "자연어 질문 / A natural-language question", true)));
        return List.of(
                new SyncPromptSpecification(connect, (context, request) -> result(connect.description(), CONNECT_TEXT)),
                new SyncPromptSpecification(why, (context, request) -> {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    String question = arguments.get("question") instanceof String value ? value : "";
                    return result(why.description(), WHY_TEXT + question);
                }));
    }

    private static McpSchema.GetPromptResult result(String description, String text) {
        return new McpSchema.GetPromptResult(description,
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }
}
