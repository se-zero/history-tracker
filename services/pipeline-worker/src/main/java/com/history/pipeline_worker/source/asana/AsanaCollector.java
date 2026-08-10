package com.history.pipeline_worker.source.asana;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AsanaCollector implements SourceCollector {

    static final String UPDATED_CURSOR = "asana_updated";

    private static final String PROJECT_GID = "project_gid";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String EXPIRES_AT = "expires_at";
    private static final TypeReference<Map<String, Object>> CREDENTIAL_JSON_TYPE = new TypeReference<>() {
    };

    private final AsanaRawService rawService;
    private final AsanaNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AsanaCollector(
            AsanaRawService rawService,
            AsanaNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            CredentialCryptoService credentialCryptoService,
            ObjectMapper objectMapper
    ) {
        this(rawService, normalizer, eventPublisher, checkpointService, credentialCryptoService, objectMapper, Clock.systemUTC());
    }

    // Clock 주입 생성자 — access_token 만료 판정을 테스트에서 고정하기 위한 seam
    public AsanaCollector(
            AsanaRawService rawService,
            AsanaNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            CredentialCryptoService credentialCryptoService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.rawService = rawService;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.checkpointService = checkpointService;
        this.credentialCryptoService = credentialCryptoService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.ASANA;
    }

    // Asana OAuth credential은 JSON({access_token, refresh_token, expires_at})이다 — Jira/Linear와 동일 구조.
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        Map<String, Object> credential = parseCredential(credentialCryptoService.decrypt(integration.encryptedCredential()));

        Instant expiresAt = parseExpiresAt(credential);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now(clock))) {
            log.warn("Asana access token is expired: projectId={}, expiresAt={}", integration.projectId(), expiresAt);
            return Optional.empty();
        }

        String accessToken = requiredCredentialString(credential, ACCESS_TOKEN);
        String projectGid = requiredString(integration.externalRef(), PROJECT_GID);

        return Optional.of(new RawFetchRequest(AuthHeaders.bearer(accessToken), projectGid, Map.of()));
    }

    // Asana /tasks는 정렬을 보장하지 않는다(문서에 ordering 부재) — 페이지 단위 전진은 truncation 시
    // 과거 변경분 영구 누락. 그래서 Slack/Linear 패턴을 따라 전체 실행이 끝까지 성공한 경우에만
    // 배치 전체의 최대 occurredAt으로 커서를 한 번 전진시킨다.
    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant since = checkpointService.loadCursors(projectId, provider()).getOrDefault(UPDATED_CURSOR, Instant.EPOCH);
        AsanaRawService.AsanaFetchContext context = rawService.prepareFetchContext(request, since);
        int totalPublished = 0;
        Instant cursor = null;
        String offset = null;
        int pageNumber = 1;

        while (true) {
            AsanaRawService.AsanaTaskPage page = rawService.fetchTaskPage(context, offset, pageNumber);
            if (page.limitReached()) {
                log.warn("Asana max pages per run 도달: queued={}", totalPublished);
                return totalPublished;
            }

            List<NormalizedEvent> pageEvents = normalizer.normalizeTasks(projectId, page.body());
            totalPublished += eventPublisher.publishAll(pageEvents);
            cursor = CursorProgress.later(cursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));

            if (!page.hasNextPage()) break;
            offset = page.nextOffset();
            pageNumber++;
        }

        checkpointService.updateCursor(projectId, provider(), UPDATED_CURSOR, cursor);
        log.info("Asana 이벤트 발행: {}", totalPublished);

        return totalPublished;
    }

    // Jira/Linear와 동일하게 Jackson 예외를 IllegalStateException으로 감싼다 — 그대로 던지면 호출부의
    // 안전망(IllegalArgumentException|IllegalStateException만 잡음)을 우회한다.
    private Map<String, Object> parseCredential(String json) {
        try {
            return objectMapper.readValue(json, CREDENTIAL_JSON_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Asana credential JSON.", exception);
        }
    }

    private Instant parseExpiresAt(Map<String, Object> credential) {
        Object value = credential.get(EXPIRES_AT);
        return value instanceof String text && !text.isBlank() ? Instant.parse(text) : null;
    }

    private String requiredCredentialString(Map<String, Object> credential, String key) {
        Object value = credential.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing Asana credential field: " + key);
    }

    private String requiredString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing external_ref value: " + key);
    }
}
