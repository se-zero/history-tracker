package com.history.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolSpecifications: MCP 도구 4종의 스키마와 핸들러 배선")
class McpToolSpecificationsTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final String WORKSPACE = "/home/me/repo";
    private static final String SECRET = "secret detail";
    private static final McpTransportContext CONTEXT = McpTransportContext.create(Map.of("userId", USER_ID.toString()));

    @Mock
    private McpQueryService queryService;

    // ── 스키마 ──

    @Test
    @DisplayName("도구 4개를 list_projects, bind_project, ask, explain_commit 순서로 돌려준다")
    void specificationsReturnsFourToolsInOrder() {
        assertThat(specifications()).extracting(spec -> spec.tool().name())
                .containsExactly("list_projects", "bind_project", "ask", "explain_commit");
    }

    @Test
    @DisplayName("모든 도구는 설명이 있고 입력 스키마가 object다")
    void everyToolHasDescriptionAndObjectSchema() {
        assertThat(specifications()).allSatisfy(spec -> {
            assertThat(spec.tool().description()).isNotBlank();
            assertThat(spec.tool().inputSchema()).containsEntry("type", "object");
        });
    }

    @Test
    @DisplayName("ask·explain_commit 설명은 답변 언어(한국어)를 안내한다")
    void askAndExplainCommitDescriptionsMentionKorean() {
        assertThat(tool("ask").description()).contains("한국어");
        assertThat(tool("explain_commit").description()).contains("한국어");
    }

    @Test
    @DisplayName("list_projects는 인자가 없다")
    void listProjectsHasNoArguments() {
        assertThat(requiredOf("list_projects")).isEmpty();
        assertThat(propertiesOf("list_projects")).isEmpty();
    }

    @Test
    @DisplayName("bind_project는 workspace·project_id가 필수다")
    void bindProjectRequiresWorkspaceAndProjectId() {
        assertThat(requiredOf("bind_project")).containsExactlyInAnyOrder("workspace", "project_id");
        assertThat(propertiesOf("bind_project").keySet()).containsExactlyInAnyOrder("workspace", "project_id");
    }

    @Test
    @DisplayName("ask는 workspace·question이 필수다")
    void askRequiresWorkspaceAndQuestion() {
        assertThat(requiredOf("ask")).containsExactlyInAnyOrder("workspace", "question");
        assertThat(propertiesOf("ask").keySet()).containsExactlyInAnyOrder("workspace", "question");
    }

    @Test
    @DisplayName("explain_commit은 workspace·hash가 필수다")
    void explainCommitRequiresWorkspaceAndHash() {
        assertThat(requiredOf("explain_commit")).containsExactlyInAnyOrder("workspace", "hash");
        assertThat(propertiesOf("explain_commit").keySet()).containsExactlyInAnyOrder("workspace", "hash");
    }

    @Test
    @DisplayName("모든 인자 속성은 string 타입과 설명을 갖는다")
    void everyPropertyIsStringWithDescription() {
        assertThat(specifications()).allSatisfy(spec ->
                assertThat(propertiesOf(spec.tool().name()).values()).allSatisfy(property -> {
                    Map<?, ?> definition = (Map<?, ?>) property;
                    assertThat(definition.get("type")).isEqualTo("string");
                    assertThat((String) definition.get("description")).isNotBlank();
                }));
    }

    // ── 핸들러 위임 ──

    @Test
    @DisplayName("list_projects: userId를 서비스에 넘기고 결과 문구를 그대로 돌려준다")
    void listProjectsDelegatesAndMapsSuccess() {
        when(queryService.listProjects(USER_ID)).thenReturn(new McpToolOutcome("프로젝트 목록", false));

        McpSchema.CallToolResult result = call("list_projects", CONTEXT, Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(textOf(result)).isEqualTo("프로젝트 목록");
    }

    @Test
    @DisplayName("bind_project: userId·workspace·project_id를 그대로 넘기고 성공 결과를 돌려준다")
    void bindProjectDelegatesAndMapsSuccess() {
        when(queryService.bindProject(USER_ID, WORKSPACE, PROJECT_ID.toString()))
                .thenReturn(new McpToolOutcome("연결했습니다", false));

        McpSchema.CallToolResult result = call("bind_project", CONTEXT,
                Map.of("workspace", WORKSPACE, "project_id", PROJECT_ID.toString()));

        assertThat(result.isError()).isFalse();
        assertThat(textOf(result)).isEqualTo("연결했습니다");
    }

    @Test
    @DisplayName("bind_project: 서비스의 오류 결과는 isError=true로 그대로 나온다")
    void bindProjectMapsErrorOutcome() {
        when(queryService.bindProject(USER_ID, WORKSPACE, PROJECT_ID.toString()))
                .thenReturn(new McpToolOutcome("프로젝트를 찾을 수 없습니다.", true));

        McpSchema.CallToolResult result = call("bind_project", CONTEXT,
                Map.of("workspace", WORKSPACE, "project_id", PROJECT_ID.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isEqualTo("프로젝트를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("ask: userId·workspace·question을 그대로 넘기고 성공 결과를 돌려준다")
    void askDelegatesAndMapsSuccess() {
        when(queryService.ask(USER_ID, WORKSPACE, "왜 바뀌었어?")).thenReturn(new McpToolOutcome("답변", false));

        McpSchema.CallToolResult result = call("ask", CONTEXT, Map.of("workspace", WORKSPACE, "question", "왜 바뀌었어?"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(textOf(result)).isEqualTo("답변");
    }

    @Test
    @DisplayName("ask: 서비스의 오류 결과는 isError=true로 그대로 나온다")
    void askMapsErrorOutcome() {
        when(queryService.ask(USER_ID, WORKSPACE, "왜 바뀌었어?"))
                .thenReturn(new McpToolOutcome("이 폴더는 아직 연결되지 않았습니다.", true));

        McpSchema.CallToolResult result = call("ask", CONTEXT, Map.of("workspace", WORKSPACE, "question", "왜 바뀌었어?"));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isEqualTo("이 폴더는 아직 연결되지 않았습니다.");
    }

    @Test
    @DisplayName("explain_commit: userId·workspace·hash를 그대로 넘기고 성공 결과를 돌려준다")
    void explainCommitDelegatesAndMapsSuccess() {
        when(queryService.explainCommit(USER_ID, WORKSPACE, "abc1234")).thenReturn(new McpToolOutcome("설명", false));

        McpSchema.CallToolResult result = call("explain_commit", CONTEXT, Map.of("workspace", WORKSPACE, "hash", "abc1234"));

        assertThat(result.isError()).isFalse();
        assertThat(textOf(result)).isEqualTo("설명");
    }

    @Test
    @DisplayName("explain_commit: 서비스의 오류 결과는 isError=true로 그대로 나온다")
    void explainCommitMapsErrorOutcome() {
        when(queryService.explainCommit(USER_ID, WORKSPACE, "zzz"))
                .thenReturn(new McpToolOutcome("hash 형식 오류", true));

        McpSchema.CallToolResult result = call("explain_commit", CONTEXT, Map.of("workspace", WORKSPACE, "hash", "zzz"));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isEqualTo("hash 형식 오류");
    }

    // ── 사용자 식별 ──

    @ParameterizedTest
    @ValueSource(strings = {"list_projects", "bind_project", "ask", "explain_commit"})
    @DisplayName("컨텍스트에 userId가 없으면 오류 결과를 돌려주고 서비스를 부르지 않는다")
    void missingUserIdReturnsErrorWithoutCallingService(String toolName) {
        McpSchema.CallToolResult result = call(toolName, McpTransportContext.EMPTY, fullArguments());

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isNotBlank();
        verifyNoInteractions(queryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"list_projects", "bind_project", "ask", "explain_commit"})
    @DisplayName("userId가 UUID 형식이 아니면 오류 결과를 돌려주고 서비스를 부르지 않는다")
    void invalidUserIdReturnsErrorWithoutCallingService(String toolName) {
        McpTransportContext context = McpTransportContext.create(Map.of("userId", "not-a-uuid"));

        McpSchema.CallToolResult result = call(toolName, context, fullArguments());

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isNotBlank();
        verifyNoInteractions(queryService);
    }

    // ── 인자 처리 ──

    @Test
    @DisplayName("arguments가 null이어도 예외 없이 인자를 null로 서비스에 넘긴다")
    void nullArgumentsArePassedAsNulls() {
        when(queryService.ask(USER_ID, null, null)).thenReturn(new McpToolOutcome("질문이 비어 있습니다.", true));

        McpSchema.CallToolResult result = call("ask", CONTEXT, null);

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isEqualTo("질문이 비어 있습니다.");
        verify(queryService).ask(USER_ID, null, null);
    }

    @Test
    @DisplayName("누락된 인자는 null로 서비스에 넘긴다")
    void missingArgumentIsPassedAsNull() {
        when(queryService.ask(USER_ID, WORKSPACE, null)).thenReturn(new McpToolOutcome("질문이 비어 있습니다.", true));

        McpSchema.CallToolResult result = call("ask", CONTEXT, Map.of("workspace", WORKSPACE));

        assertThat(result.isError()).isTrue();
        verify(queryService).ask(USER_ID, WORKSPACE, null);
    }

    @Test
    @DisplayName("String이 아닌 인자(숫자)는 null로 서비스에 넘긴다")
    void nonStringArgumentIsPassedAsNull() {
        when(queryService.explainCommit(USER_ID, WORKSPACE, null)).thenReturn(new McpToolOutcome("hash 형식 오류", true));

        McpSchema.CallToolResult result = call("explain_commit", CONTEXT, Map.of("workspace", WORKSPACE, "hash", 1234567));

        assertThat(result.isError()).isTrue();
        verify(queryService).explainCommit(USER_ID, WORKSPACE, null);
    }

    @Test
    @DisplayName("bind_project도 arguments가 null이면 두 인자를 null로 넘긴다")
    void bindProjectWithNullArgumentsPassesNulls() {
        when(queryService.bindProject(USER_ID, null, null)).thenReturn(new McpToolOutcome("형식 오류", true));

        McpSchema.CallToolResult result = call("bind_project", CONTEXT, null);

        assertThat(result.isError()).isTrue();
        verify(queryService).bindProject(USER_ID, null, null);
    }

    // ── 예외 격리 ──

    @ParameterizedTest
    @ValueSource(strings = {"list_projects", "bind_project", "ask", "explain_commit"})
    @DisplayName("서비스가 예외를 던져도 isError 결과로 바꾸고 예외 메시지는 문구에 넣지 않는다")
    void serviceExceptionBecomesErrorResultWithoutLeakingMessage(String toolName) {
        McpQueryService failingService = mock(McpQueryService.class, withSettings().defaultAnswer(invocation -> {
            throw new RuntimeException(SECRET);
        }));
        List<SyncToolSpecification> specs = new McpToolSpecifications(failingService).specifications();
        SyncToolSpecification spec = specs.stream().filter(s -> s.tool().name().equals(toolName)).findFirst().orElseThrow();

        McpSchema.CallToolResult result = spec.callHandler().apply(CONTEXT, request(toolName, fullArguments()));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isNotBlank().doesNotContain(SECRET);
    }

    // ── 헬퍼 ──

    private List<SyncToolSpecification> specifications() {
        return new McpToolSpecifications(queryService).specifications();
    }

    private McpSchema.Tool tool(String name) {
        return specifications().stream().map(SyncToolSpecification::tool)
                .filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredOf(String name) {
        Object required = tool(name).inputSchema().get("required");
        return required == null ? List.of() : (List<String>) required;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertiesOf(String name) {
        Object properties = tool(name).inputSchema().get("properties");
        return properties == null ? Map.of() : (Map<String, Object>) properties;
    }

    private McpSchema.CallToolResult call(String name, McpTransportContext context, Map<String, Object> arguments) {
        SyncToolSpecification spec = specifications().stream()
                .filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
        return spec.callHandler().apply(context, request(name, arguments));
    }

    private McpSchema.CallToolRequest request(String name, Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(name, arguments);
    }

    // 도구 4종이 받는 모든 인자 이름을 채운 맵 — 도구가 안 쓰는 키는 무시된다
    private Map<String, Object> fullArguments() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("workspace", WORKSPACE);
        arguments.put("project_id", PROJECT_ID.toString());
        arguments.put("question", "왜 바뀌었어?");
        arguments.put("hash", "abc1234");
        return arguments;
    }

    private String textOf(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
