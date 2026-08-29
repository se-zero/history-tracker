package com.history.backend.slack.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.service.IntegrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

// Slack Events API 라이프사이클 이벤트 처리 — app_uninstalled / tokens_revoked
@Slf4j
@Service
public class SlackEventsService {

    private final SlackSignatureVerifier verifier;
    private final IntegrationService integrationService;
    private final TaskExecutor slackEventsTaskExecutor;
    private final ObjectMapper objectMapper;

    // Spring 빈 등록 생성자 — 이 프로젝트는 ObjectMapper를 Spring 빈으로 등록하지 않아 직접 생성한다
    @Autowired
    public SlackEventsService(
            SlackSignatureVerifier verifier,
            IntegrationService integrationService,
            @Qualifier("slackEventsTaskExecutor") TaskExecutor slackEventsTaskExecutor
    ) {
        this(verifier, integrationService, slackEventsTaskExecutor, new ObjectMapper());
    }

    // 테스트 전용 생성자 — 실제 ObjectMapper 주입으로 JSON 파싱 동작을 검증한다
    SlackEventsService(
            SlackSignatureVerifier verifier,
            IntegrationService integrationService,
            TaskExecutor slackEventsTaskExecutor,
            ObjectMapper objectMapper
    ) {
        this.verifier = verifier;
        this.integrationService = integrationService;
        this.slackEventsTaskExecutor = slackEventsTaskExecutor;
        this.objectMapper = objectMapper;
    }

    // 서명 검증 후 이벤트 타입에 따라 연동 해제를 executor에 위임한다.
    // JSON 파싱 실패는 삼키고 200으로 응답 — 파싱 불가 이벤트로 Slack 구독이 끊기면 안 된다.
    public SlackEventAck handle(String timestamp, String signature, String rawBody) {
        if (!verifier.verify(timestamp, signature, rawBody)) {
            throw new UnauthorizedException("Invalid Slack request signature.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Slack Events: JSON 파싱 실패, 구독 유지를 위해 200 반환. error={}", e.getMessage());
            return new SlackEventAck(200, null);
        }
        String type = root.path("type").asText(null);
        if ("url_verification".equals(type)) {
            // executor를 거치지 않고 즉시 반환 — executor가 거부해도 challenge를 응답해야 한다
            String challenge = root.path("challenge").isNull() ? null : root.path("challenge").asText(null);
            return new SlackEventAck(200, challenge);
        }
        if ("event_callback".equals(type)) {
            String teamId = root.path("team_id").asText(null);
            JsonNode event = root.path("event");
            String eventType = event.path("type").asText(null);
            if ("app_uninstalled".equals(eventType)) {
                if (teamId != null && !teamId.isBlank()) {
                    // 팀 전체 연동 해제는 짧은 처리라도 executor에서 비동기로 — Slack 3초 응답 규칙을 지킨다
                    enqueueLifecycle("app_uninstalled", teamId, () -> integrationService.disconnectSlackWorkspace(teamId));
                }
                return new SlackEventAck(200, null);
            }
            if ("tokens_revoked".equals(eventType)) {
                if (teamId != null && !teamId.isBlank()) {
                    JsonNode oauthNode = event.path("tokens").path("oauth");
                    if (!oauthNode.isMissingNode() && !oauthNode.isNull() && oauthNode.isArray() && !oauthNode.isEmpty()) {
                        List<String> oauthIds = toStringList(oauthNode);
                        // oauth 사용자 목록이 있을 때만 executor에 올린다
                        enqueueLifecycle("tokens_revoked", teamId, () -> integrationService.disconnectSlackUsers(teamId, oauthIds));
                    }
                }
                return new SlackEventAck(200, null);
            }
            // 알 수 없는 event.type — 구독 끊김 방지를 위해 200 반환
            return new SlackEventAck(200, null);
        }
        // 알 수 없는 type — 200 반환
        return new SlackEventAck(200, null);
    }

    // JSON 배열 → List<String> 변환 (입력 순서 보존 — Set이면 tokens_revoked 검증이 실패한다)
    private List<String> toStringList(JsonNode arrayNode) {
        List<String> ids = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            ids.add(element.asText());
        }
        return ids;
    }

    // 큐가 꽉 차면 execute가 예외를 던져 컨트롤러 5xx → Slack 재시도.
    // 제출 이후 실패는 이미 200을 준 뒤라 재시도가 없다. 스레드만 죽이지 말고 원인을 남긴다.
    private void enqueueLifecycle(String eventLabel, String teamId, Runnable task) {
        slackEventsTaskExecutor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Slack Events: {} 처리 실패. teamId={}, error={}", eventLabel, teamId, e.getMessage(), e);
            }
        });
    }
}
