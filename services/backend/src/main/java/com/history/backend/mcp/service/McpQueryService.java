package com.history.backend.mcp.service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    // 결과·오류 문구는 영어 한 벌이다. 1차로 읽는 것은 코딩 에이전트이고, 사용자에게는 에이전트가 사용자 언어로 옮겨 전한다.
    private static final String INVALID_WORKSPACE = "workspace must be the absolute path of the current working folder.";
    private static final String NO_PROJECTS = "You have no whycode projects yet. Create one at %s first.";
    private static final String UNBOUND = "This folder is not connected to a whycode project yet. "
            + "Show the list below to the user, ask which project it is, then call bind_project(workspace, project_id). "
            + "Confirm with the user even if there is only one project.\n%s";
    private static final String PROJECT_LIST_HEADER = "whycode projects:\n%s";
    private static final String BOUND = "Connected this folder to the whycode project \"%s\".";
    private static final String INVALID_PROJECT_ID = "project_id is not a valid id. Check it with list_projects.";
    private static final String PROJECT_NOT_FOUND = "Project not found. Check the project id with list_projects.";
    private static final String EMPTY_QUESTION = "question is empty.";
    private static final String INVALID_HASH = "hash must be a commit hash of 7-40 hex characters. Get it with git blame or git log.";
    private static final String INVALID_LANGUAGE = "language must be \"ko\" or \"en\".";
    private static final String PLAN_LIMIT = "You have reached the free plan's question limit. See plans at %s/pricing.";
    private static final String RATE_LIMITED = "Too many questions. Try again in %d seconds.";
    private static final String TIMEOUT = "whycode couldn't produce an answer within %d seconds. Please try again shortly.";
    private static final String GENERIC_ERROR = "whycode failed to generate an answer. Please try again shortly.";

    // ai-engine은 지정 노드가 있는 질문의 발화 판별을 건너뛰고 질문 글로 답변 언어를 정하므로, 언어는 이 문장이 싣는다.
    // 두 문장은 ai-engine 판정 테스트(tests/unit/test_answer_language.py)가 각각 ko·en으로 고정한다 — 바꾸면 함께 고친다.
    private static final Map<String, String> EXPLAIN_COMMIT_QUESTIONS = Map.of(
            "ko", "커밋 %s가 왜 이렇게 바뀌었는지 근거와 함께 설명해줘",
            "en", "Explain, with evidence, why commit %s changed the way it did.");
    private static final String DEFAULT_LANGUAGE = "ko";

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

    public McpToolOutcome explainCommit(UUID userId, String workspace, String hash, String language) {
        if (hash == null || !COMMIT_HASH.matcher(hash).matches()) {
            return error(INVALID_HASH);
        }
        String normalized = language == null || language.isBlank()
                ? DEFAULT_LANGUAGE
                : language.strip().toLowerCase(Locale.ROOT);
        String question = EXPLAIN_COMMIT_QUESTIONS.get(normalized);
        if (question == null) {
            return error(INVALID_LANGUAGE);
        }
        return query(userId, workspace, question.formatted(hash), List.of(new EvidenceRef("commit", hash)));
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
