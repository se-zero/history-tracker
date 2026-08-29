package com.history.backend.slack.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.history.backend.auth.service.PlanService;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.service.AiEngineQueryClient;
import com.history.backend.conversation.service.AiEngineQueryResult;
import com.history.backend.conversation.service.MessageService;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.integration.service.IntegrationService.SlackCommandTarget;
import com.history.backend.integration.service.SlackCredential;
import com.history.backend.integration.service.SlackCredentialCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

// /why-code 슬래시 커맨드 — Slack 3초 ack 안에 질의를 끝낼 수 없어 executor에 맡기고 response_url로 답한다.
@Slf4j
@Service
public class SlackCommandsService {

    private static final String RESPONSE_TYPE = "ephemeral";
    private static final String SEARCHING = "질문을 찾고 있어요. 잠시만 기다려 주세요.";
    private static final String BUSY = "지금은 요청이 많아요. 잠시 후 다시 시도해 주세요.";
    private static final String QUERY_FAILED = "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
    private static final String GATING =
            "이 워크스페이스를 연결한 계정만 사용할 수 있어요. https://why-code.com";
    private static final String PLAN_LIMIT =
            "무료 플랜의 질문 한도에 도달했어요. https://why-code.com 에서 플랜을 확인해주세요.";
    private static final String USAGE =
            "사용법: `/why-code 질문` — 연결된 프로젝트의 코드 변경 맥락을 찾습니다.\n"
                    + "프로젝트가 여러 개면 `/why-code [이름] 질문` 으로 지정하세요.\n"
                    + "`/why-code help` 로 이 안내를 다시 볼 수 있습니다.";
    private static final Pattern PROJECT_SELECTOR = Pattern.compile("^\\[(.+)]\\s*(.*)$");

    private final SlackSignatureVerifier verifier;
    private final IntegrationService integrationService;
    private final SlackCredentialCodec slackCredentialCodec;
    private final SlackClient slackClient;
    private final PlanService planService;
    private final AiEngineQueryClient aiEngineQueryClient;
    @SuppressWarnings("unused")
    private final ConversationRepository conversationRepository;
    @SuppressWarnings("unused")
    private final MessageService messageService;
    private final TaskExecutor slackCommandsTaskExecutor;

    public SlackCommandsService(
            SlackSignatureVerifier verifier,
            IntegrationService integrationService,
            SlackCredentialCodec slackCredentialCodec,
            SlackClient slackClient,
            PlanService planService,
            AiEngineQueryClient aiEngineQueryClient,
            ConversationRepository conversationRepository,
            MessageService messageService,
            @Qualifier("slackCommandsTaskExecutor") TaskExecutor slackCommandsTaskExecutor
    ) {
        this.verifier = verifier;
        this.integrationService = integrationService;
        this.slackCredentialCodec = slackCredentialCodec;
        this.slackClient = slackClient;
        this.planService = planService;
        this.aiEngineQueryClient = aiEngineQueryClient;
        // 단발 질의라 대화에 쓰지 않는다 — 생성자 자리는 테스트가 고정한다
        this.conversationRepository = conversationRepository;
        this.messageService = messageService;
        this.slackCommandsTaskExecutor = slackCommandsTaskExecutor;
    }

    // 서명 검증 후 사용법은 HTTP 본문으로 즉시, 질의는 executor에 올린 뒤 "찾는 중" ack.
    public SlackCommandAck handle(String timestamp, String signature, String rawBody) {
        if (!verifier.verify(timestamp, signature, rawBody)) {
            throw new UnauthorizedException("Invalid Slack request signature.");
        }
        SlackCommandForm form = parseForm(rawBody);
        if (form == null
                || isBlank(form.teamId())
                || isBlank(form.userId())
                || isBlank(form.responseUrl())
                || isBlank(form.text())
                || "help".equalsIgnoreCase(form.text().trim())) {
            return new SlackCommandAck(RESPONSE_TYPE, USAGE);
        }
        String teamId = form.teamId();
        String userId = form.userId();
        String text = form.text();
        String responseUrl = form.responseUrl();
        try {
            slackCommandsTaskExecutor.execute(() -> {
                try {
                    runCommand(teamId, userId, text, responseUrl);
                } catch (RuntimeException e) {
                    log.error("Slack command query failed. teamId={}, error={}", teamId, e.getMessage(), e);
                    slackClient.postEphemeral(responseUrl, QUERY_FAILED);
                }
            });
        } catch (TaskRejectedException e) {
            // 큐가 가득 차면 5xx를 주면 Slack이 재시도한다 — 이미 바쁜 상태를 안내하는 편이 맞다
            return new SlackCommandAck(RESPONSE_TYPE, BUSY);
        }
        return new SlackCommandAck(RESPONSE_TYPE, SEARCHING);
    }

    private void runCommand(String teamId, String userId, String text, String responseUrl) {
        List<SlackCommandTarget> candidates = resolveCandidates(teamId, userId);
        if (candidates.isEmpty()) {
            slackClient.postEphemeral(responseUrl, GATING);
            return;
        }
        if (candidates.size() == 1) {
            ask(candidates.get(0), text, responseUrl);
            return;
        }
        Matcher selector = PROJECT_SELECTOR.matcher(text);
        if (selector.matches()) {
            String name = selector.group(1);
            List<SlackCommandTarget> matched = candidates.stream()
                    .filter(target -> target.projectName().equalsIgnoreCase(name))
                    .toList();
            if (matched.size() == 1) {
                ask(matched.get(0), selector.group(2).trim(), responseUrl);
                return;
            }
        }
        slackClient.postEphemeral(responseUrl, listProjects(candidates));
    }

    private List<SlackCommandTarget> resolveCandidates(String teamId, String userId) {
        List<SlackCommandTarget> candidates = new ArrayList<>();
        for (SlackCommandTarget target : integrationService.listSlackCommandTargets(teamId)) {
            String connectedUserId = target.connectedUserId();
            if (userId.equals(connectedUserId)) {
                candidates.add(target);
                continue;
            }
            if (connectedUserId != null) {
                continue;
            }
            String authedUserId;
            try {
                SlackCredential credential = slackCredentialCodec.decrypt(target.encryptedCredential());
                authedUserId = slackClient.authTest(credential.userToken());
            } catch (RuntimeException e) {
                // 한 행의 복호화·auth.test 실패가 같은 워크스페이스의 다른 후보를 막으면 안 된다
                log.warn("Slack command skipped corrupt legacy credential. integrationId={}, error={}",
                        target.integrationId(), e.getMessage());
                continue;
            }
            if (!userId.equals(authedUserId)) {
                continue;
            }
            integrationService.backfillSlackConnectedUserId(target.integrationId(), userId);
            candidates.add(target);
        }
        return candidates;
    }

    private void ask(SlackCommandTarget target, String question, String responseUrl) {
        try {
            planService.ensureQueryAllowed(target.ownerUserId());
        } catch (PlanLimitExceededException e) {
            slackClient.postEphemeral(responseUrl, PLAN_LIMIT);
            return;
        }
        planService.recordQuery(target.ownerUserId());
        AiEngineQueryResult result = aiEngineQueryClient.ask(
                question, target.projectId(), List.of(), List.of(), null, List.of());
        if (result.fallback()) {
            slackClient.postEphemeral(responseUrl, QUERY_FAILED);
            return;
        }
        slackClient.postEphemeral(responseUrl, result.answer());
    }

    private static String listProjects(List<SlackCommandTarget> candidates) {
        StringBuilder text = new StringBuilder("연결된 프로젝트가 여러 개예요. `/why-code [이름] 질문` 으로 지정해 주세요.\n");
        for (SlackCommandTarget target : candidates) {
            text.append("• ").append(target.projectName()).append('\n');
        }
        return text.toString();
    }

    private static SlackCommandForm parseForm(String rawBody) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : rawBody.split("&")) {
                int eq = pair.indexOf('=');
                String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
                String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
                fields.put(
                        URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
                        URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            }
            return new SlackCommandForm(
                    fields.get("team_id"),
                    fields.get("user_id"),
                    fields.get("text"),
                    fields.get("response_url"));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SlackCommandForm(String teamId, String userId, String text, String responseUrl) {
    }
}
