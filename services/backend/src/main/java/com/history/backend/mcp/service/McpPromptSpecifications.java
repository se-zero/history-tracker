package com.history.backend.mcp.service;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

// 에이전트 UI에서 사용자가 직접 고르는 슬래시 프롬프트(connect·why). 도구 호출 순서를 문장으로 안내할 뿐 로직은 없다.
@Component
public class McpPromptSpecifications {

    private static final String CONNECT_TEXT = "현재 작업 폴더를 whycode 프로젝트에 연결해 주세요. "
            + "list_projects로 프로젝트 목록을 받아 사용자에게 어느 프로젝트인지 고르게 한 뒤(하나뿐이어도 확인), "
            + "현재 작업 폴더의 절대 경로를 workspace로 넘겨 bind_project를 호출하세요.";

    private static final String WHY_TEXT = "다음 질문에 whycode 도구로 답해 주세요. "
            + "특정 줄이나 코드 조각에 대한 질문이면 git blame으로 커밋 해시를 먼저 구해 explain_commit을, 그 외에는 ask를 씁니다. "
            + "workspace는 현재 작업 폴더의 절대 경로입니다.\n\n질문: ";

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
