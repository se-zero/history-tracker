package com.history.backend.mcp.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.history.backend.auth.service.PlanService;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.conversation.service.AiEngineQueryClient;
import com.history.backend.conversation.service.AiEngineQueryResult;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.mcp.service.McpBindingService.Bound;
import com.history.backend.mcp.service.McpBindingService.NoProjects;
import com.history.backend.mcp.service.McpBindingService.Resolution;
import com.history.backend.mcp.service.McpBindingService.Unbound;
import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// MCP 도구 4종(list_projects·bind_project·ask·explain_commit)의 실제 동작. MCP SDK와 무관한 순수 서비스다.
// 질의 순서·한도는 Slack /why-code와 같고 대화는 저장하지 않는다.
// @Transactional을 붙이지 않는다 — ai-engine 호출(최대 read timeout) 동안 DB 커넥션을 잡지 않기 위해서다.
// 트랜잭션은 McpBindingService·PlanService가 각자 갖는다.
@Slf4j
@Service
public class McpQueryService {

    private static final Pattern COMMIT_HASH = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    private static final String INVALID_WORKSPACE = "workspace는 현재 작업 폴더의 절대 경로여야 합니다.";
    private static final String NO_PROJECTS = "whycode에 프로젝트가 없습니다. %s 에서 프로젝트를 먼저 만들어 주세요.";
    private static final String UNBOUND = "이 폴더는 아직 whycode 프로젝트에 연결되지 않았습니다. "
            + "아래 목록을 사용자에게 보여 주고 어느 프로젝트인지 물어본 뒤 bind_project(workspace, project_id)를 호출하세요. "
            + "프로젝트가 하나뿐이어도 사용자에게 확인하세요.\n%s";
    private static final String PROJECT_LIST_HEADER = "whycode 프로젝트 목록:\n%s";
    private static final String BOUND = "이 폴더를 whycode 프로젝트 \"%s\"에 연결했습니다.";
    private static final String INVALID_PROJECT_ID = "project_id 형식이 올바르지 않습니다. list_projects로 프로젝트 id를 확인하세요.";
    private static final String PROJECT_NOT_FOUND = "프로젝트를 찾을 수 없습니다. list_projects로 프로젝트 id를 확인하세요.";
    private static final String EMPTY_QUESTION = "question이 비어 있습니다.";
    private static final String INVALID_HASH = "hash는 7~40자의 16진수 커밋 해시여야 합니다. git blame이나 git log로 해시를 확인하세요.";
    private static final String PLAN_LIMIT = "무료 플랜의 질문 한도에 도달했습니다. %s/pricing 에서 플랜을 확인해 주세요.";
    private static final String RATE_LIMITED = "질문이 너무 잦습니다. %d초 뒤에 다시 시도해 주세요.";
    private static final String TIMEOUT = "whycode가 %d초 안에 답을 만들지 못했습니다. 잠시 뒤 다시 시도해 주세요.";
    private static final String GENERIC_ERROR = "whycode 답변 생성 중 오류가 났습니다. 잠시 뒤 다시 시도해 주세요.";

    private final McpBindingService bindingService;
    private final ProjectService projectService;
    private final PlanService planService;
    private final McpRateLimiter rateLimiter;
    private final AiEngineQueryClient aiEngineQueryClient;
    private final McpOAuthProperties oAuthProperties;
    private final int aiEngineReadTimeoutSeconds;

    public McpQueryService(
            McpBindingService bindingService,
            ProjectService projectService,
            PlanService planService,
            McpRateLimiter rateLimiter,
            AiEngineQueryClient aiEngineQueryClient,
            McpOAuthProperties oAuthProperties,
            @Value("${ai.engine.read-timeout-seconds:120}") int aiEngineReadTimeoutSeconds
    ) {
        this.bindingService = bindingService;
        this.projectService = projectService;
        this.planService = planService;
        this.rateLimiter = rateLimiter;
        this.aiEngineQueryClient = aiEngineQueryClient;
        this.oAuthProperties = oAuthProperties;
        this.aiEngineReadTimeoutSeconds = aiEngineReadTimeoutSeconds;
    }

    public McpToolOutcome listProjects(UUID userId) {
        List<Project> projects = projectService.findProjects(userId);
        if (projects.isEmpty()) {
            return new McpToolOutcome(NO_PROJECTS.formatted(oAuthProperties.issuer()), false);
        }
        return new McpToolOutcome(PROJECT_LIST_HEADER.formatted(projectLines(projects)), false);
    }

    public McpToolOutcome bindProject(UUID userId, String workspace, String projectId) {
        UUID id;
        try {
            id = UUID.fromString(projectId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return error(INVALID_PROJECT_ID);
        }
        try {
            Project project = bindingService.bind(userId, workspace, id);
            return new McpToolOutcome(BOUND.formatted(project.getName()), false);
        } catch (IllegalArgumentException e) {
            return error(INVALID_WORKSPACE);
        } catch (NotFoundException | ForbiddenException e) {
            // 없는 프로젝트와 남의 프로젝트를 같은 문구로 답해 존재 여부를 드러내지 않는다
            return error(PROJECT_NOT_FOUND);
        }
    }

    public McpToolOutcome ask(UUID userId, String workspace, String question) {
        if (question == null || question.isBlank()) {
            return error(EMPTY_QUESTION);
        }
        return query(userId, workspace, question, List.of());
    }

    public McpToolOutcome explainCommit(UUID userId, String workspace, String hash) {
        if (hash == null || !COMMIT_HASH.matcher(hash).matches()) {
            return error(INVALID_HASH);
        }
        return query(userId, workspace, "커밋 " + hash + "가 왜 이렇게 바뀌었는지 근거와 함께 설명해줘",
                List.of(new EvidenceRef("commit", hash)));
    }

    // 분당 상한을 질의 수 기록보다 앞에 둬, 상한에 걸린 호출이 월 한도를 깎지 않게 한다
    private McpToolOutcome query(UUID userId, String workspace, String question, List<EvidenceRef> focus) {
        Resolution resolution;
        try {
            resolution = bindingService.resolve(userId, workspace);
        } catch (IllegalArgumentException e) {
            return error(INVALID_WORKSPACE);
        }
        if (resolution instanceof NoProjects) {
            return error(NO_PROJECTS.formatted(oAuthProperties.issuer()));
        }
        if (resolution instanceof Unbound unbound) {
            return error(UNBOUND.formatted(projectLines(unbound.projects())));
        }
        Project project = ((Bound) resolution).project();

        try {
            planService.ensureQueryAllowed(userId);
            Duration wait = rateLimiter.acquire(userId);
            if (!wait.isZero() && !wait.isNegative()) {
                long seconds = (wait.toMillis() + 999) / 1000;
                return error(RATE_LIMITED.formatted(seconds));
            }
            planService.recordQuery(userId);
        } catch (PlanLimitExceededException e) {
            return error(PLAN_LIMIT.formatted(oAuthProperties.issuer()));
        }

        AiEngineQueryResult result = aiEngineQueryClient.ask(question, project.getId(), List.of(), List.of(), null, focus);
        if (result.fallback()) {
            log.warn("MCP query fell back: userId={}, projectId={}, reason={}", userId, project.getId(), result.reason());
            return error(result.reason() == AiEngineQueryResult.FallbackReason.TIMEOUT
                    ? TIMEOUT.formatted(aiEngineReadTimeoutSeconds)
                    : GENERIC_ERROR);
        }
        return new McpToolOutcome(result.answer(), false);
    }

    private String projectLines(List<Project> projects) {
        return String.join("\n", projects.stream()
                .map(project -> "- " + project.getName() + " (project_id: " + project.getId() + ")")
                .toList());
    }

    private McpToolOutcome error(String text) {
        return new McpToolOutcome(text, true);
    }
}
