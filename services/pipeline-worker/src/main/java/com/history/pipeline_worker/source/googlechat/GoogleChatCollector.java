package com.history.pipeline_worker.source.googlechat;

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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class GoogleChatCollector implements SourceCollector {

    static final String MESSAGES_CURSOR = "google_chat_messages";

    private static final String SPACE_ID = "space_id";
    private static final String ACCESS_TOKEN = "access_token";
    private static final TypeReference<Map<String, Object>> CREDENTIAL_JSON_TYPE = new TypeReference<>() {
    };

    private final GoogleChatRawService rawService;
    private final GoogleChatNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper;

    public GoogleChatCollector(
            GoogleChatRawService rawService,
            GoogleChatNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            CredentialCryptoService credentialCryptoService,
            ObjectMapper objectMapper
    ) {
        this.rawService = rawService;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.checkpointService = checkpointService;
        this.credentialCryptoService = credentialCryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.GOOGLE_CHAT;
    }

    // Jira와 같은 모양이다 — 만료되는 사용자 access token이 JSON({access_token, refresh_token,
    // expires_at})으로 저장돼 있다. 갱신은 backend(GoogleChatTokenService)가 전담하므로 여기서는
    // 저장된 access_token을 그대로 읽어 Bearer로 감싼다.
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        Map<String, Object> credential = parseCredential(credentialCryptoService.decrypt(integration.encryptedCredential()));
        String accessToken = requiredCredentialString(credential, ACCESS_TOKEN);
        String spaceId = requiredString(integration.externalRef(), SPACE_ID);

        return Optional.of(new RawFetchRequest(AuthHeaders.bearer(accessToken), spaceId, Map.of()));
    }

    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant lastScannedAt = checkpointService.loadCursors(projectId, provider()).get(MESSAGES_CURSOR);
        GoogleChatRawService.GoogleChatFetchContext context = rawService.prepareFetchContext(request, lastScannedAt);

        String spaceDisplayName = rawService.fetchSpaceDisplayName(context);
        List<Map<String, Object>> messages = rawService.fetchMessages(context);
        // 사용자 인증으로는 Message.sender에 displayName이 오지 않는다(실측 확인) — People API로
        // 별도 보강한다. 메시지에 실제 등장한 sender만 조회해 불필요한 호출을 피한다.
        Map<String, GoogleChatRawService.PersonInfo> actorInfo =
                rawService.resolveSenders(context.auth(), senderNames(messages));
        List<NormalizedEvent> events = normalizer.normalizeMessages(projectId, spaceDisplayName, messages, actorInfo);

        int published = eventPublisher.publishAll(events);
        CursorProgress.maxOccurredAt(events).ifPresent(cursor ->
                checkpointService.updateCursor(projectId, provider(), MESSAGES_CURSOR, cursor));

        log.info("Google Chat 이벤트 발행: {}", published);
        return published;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> senderNames(List<Map<String, Object>> messages) {
        Set<String> senderNames = new HashSet<>();
        for (Map<String, Object> message : messages) {
            Object sender = message.get("sender");
            if (sender instanceof Map<?, ?> senderMap && senderMap.get("name") instanceof String name) {
                senderNames.add(name);
            }
        }
        return senderNames;
    }

    // Jira credential과 같은 이유로 Jackson 예외를 IllegalStateException으로 감싼다 — 호출부의
    // 안전망(IllegalStateException|IllegalArgumentException만 잡음)을 우회하지 않기 위함이다.
    private Map<String, Object> parseCredential(String json) {
        try {
            return objectMapper.readValue(json, CREDENTIAL_JSON_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Google Chat credential JSON.", exception);
        }
    }

    String requiredCredentialString(Map<String, Object> credential, String key) {
        Object value = credential.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing Google Chat credential field: " + key);
    }

    private String requiredString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing external_ref value: " + key);
    }
}
