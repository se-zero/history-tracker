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
        GoogleChatRawService.GoogleChatFetchContext context = rawService.prepareFetchContext(request, lastScannedAt, projectId);

        String spaceDisplayName = rawService.fetchSpaceDisplayName(context);

        // 스페이스 전체를 모으지 않고 페이지마다 발행한다(Slack·Discord와 같은 이유) — 발행 배치와
        // 메모리가 스페이스 크기에 비례하면 수년치 스페이스가 confirm 타임아웃에 걸려 재시도해도
        // 계속 실패한다. 페이지 크기(1000)가 곧 발행 배치 상한이 된다.
        int published = 0;
        Instant cursor = null;
        String pageToken = null;
        do {
            GoogleChatRawService.GoogleChatMessagePage page = rawService.fetchMessagePage(context, pageToken);
            // 사용자 인증으로는 Message.sender에 displayName이 오지 않는다(실측 확인) — People API로
            // 별도 보강한다. 페이지마다 불러도 context가 실행 단위로 조회 결과를 재사용해 호출 수가
            // 페이지 수에 비례하지 않는다(첫 페이지 이후로는 대부분 재사용 히트다).
            Map<String, GoogleChatRawService.PersonInfo> actorInfo =
                    rawService.resolveSenders(context, senderNames(page.messages()));
            List<NormalizedEvent> events =
                    normalizer.normalizeMessages(projectId, spaceDisplayName, page.messages(), actorInfo);

            published += eventPublisher.publishAll(events);
            cursor = CursorProgress.later(cursor, CursorProgress.maxOccurredAt(events).orElse(null));
            pageToken = page.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());

        // 전체 성공 후 한 번만 전진한다 — 중간 페이지 발행이 실패하면 예외가 전파돼 여기 도달하지
        // 못하므로 checkpoint가 그대로 남고 다음 실행에서 재발행된다(전량 축적하던 때와 같은 보증).
        checkpointService.updateCursor(projectId, provider(), MESSAGES_CURSOR, cursor);

        log.info("Google Chat 이벤트 발행: {}", published);
        return published;
    }

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
