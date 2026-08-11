package com.history.pipeline_worker.source.clickup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.checkpoint.CursorProgress;
import com.history.pipeline_worker.collection.AuthHeaders;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.collection.SourceCollector;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickUpCollector implements SourceCollector {

    static final String UPDATED_CURSOR = "clickup_updated";

    private static final String WORKSPACE_ID = "workspace_id";
    private static final String LIST_ID = "list_id";
    private static final String ACCESS_TOKEN = "access_token";
    private static final TypeReference<Map<String, Object>> CREDENTIAL_JSON_TYPE = new TypeReference<>() {
    };

    private final ClickUpRawService rawService;
    private final ClickUpNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper;

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.CLICKUP;
    }

    // ClickUp OAuth credential은 JSON({access_token})이다. ClickUp 공식 문서 기준 access_token에
    // 만료 개념이 없어 Asana처럼 expires_at 판정 분기를 두지 않는다.
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        Map<String, Object> credential = parseCredential(credentialCryptoService.decrypt(integration.encryptedCredential()));

        String accessToken = requiredCredentialString(credential, ACCESS_TOKEN);
        String workspaceId = requiredString(integration.externalRef(), WORKSPACE_ID);
        String listId = requiredString(integration.externalRef(), LIST_ID);

        return Optional.of(new RawFetchRequest(AuthHeaders.bearer(accessToken), workspaceId, Map.of(LIST_ID, listId)));
    }

    // ClickUp /task 응답도 정렬 순서를 보장하지 않아 Slack/Asana 패턴을 따른다 — 전체 실행이 끝까지
    // 성공한 경우에만 배치 전체의 최대 occurredAt으로 커서를 한 번 전진시킨다. 페이지 진행은 offset
    // 토큰이 아니라 0부터 시작하는 page 번호다.
    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant since = checkpointService.loadCursors(projectId, provider()).getOrDefault(UPDATED_CURSOR, Instant.EPOCH);
        ClickUpRawService.ClickUpFetchContext context = rawService.prepareFetchContext(request, since);
        int totalPublished = 0;
        Instant cursor = null;
        int page = 0;

        while (true) {
            ClickUpRawService.ClickUpTaskPage taskPage = rawService.fetchTaskPage(context, page);
            if (taskPage.limitReached()) {
                log.warn("ClickUp max pages per run 도달: queued={}", totalPublished);
                return totalPublished;
            }

            List<NormalizedEvent> pageEvents = normalizer.normalizeTasks(projectId, taskPage.body());
            totalPublished += eventPublisher.publishAll(pageEvents);
            cursor = CursorProgress.later(cursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));

            if (!taskPage.hasNextPage()) break;
            page++;
        }

        checkpointService.updateCursor(projectId, provider(), UPDATED_CURSOR, cursor);
        log.info("ClickUp 이벤트 발행: {}", totalPublished);

        return totalPublished;
    }

    // Jira/Linear/Asana와 동일하게 Jackson 예외를 IllegalStateException으로 감싼다 — 그대로 던지면
    // 호출부의 안전망(IllegalArgumentException|IllegalStateException만 잡음)을 우회한다.
    private Map<String, Object> parseCredential(String json) {
        try {
            return objectMapper.readValue(json, CREDENTIAL_JSON_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse ClickUp credential JSON.", exception);
        }
    }

    private String requiredCredentialString(Map<String, Object> credential, String key) {
        Object value = credential.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing ClickUp credential field: " + key);
    }

    private String requiredString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing external_ref value: " + key);
    }
}
