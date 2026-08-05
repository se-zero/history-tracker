package com.history.pipeline_worker.source.jira;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class JiraCollector implements SourceCollector {

    static final String UPDATED_CURSOR = "jira_updated";

    private static final String PROJECT_KEY = "project_key";
    private static final String CLOUD_ID = "cloud_id";
    private static final String ACCESS_TOKEN = "access_token";
    private static final TypeReference<Map<String, Object>> CREDENTIAL_JSON_TYPE = new TypeReference<>() {
    };

    private final JiraRawService rawService;
    private final JiraNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper;
    private final String gatewayBaseUrl;

    public JiraCollector(
            JiraRawService rawService,
            JiraNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            CredentialCryptoService credentialCryptoService,
            ObjectMapper objectMapper,
            @Value("${app.jira.gateway-base-url}") String gatewayBaseUrl
    ) {
        this.rawService = rawService;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.checkpointService = checkpointService;
        this.credentialCryptoService = credentialCryptoService;
        this.objectMapper = objectMapper;
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.JIRA;
    }

    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        Map<String, Object> credential = parseCredential(credentialCryptoService.decrypt(integration.encryptedCredential()));

        String accessToken = requiredCredentialString(credential, ACCESS_TOKEN);
        String projectKey = requiredString(integration.externalRef(), PROJECT_KEY);
        String baseUrl = gatewayBaseUrl + "/" + requiredString(integration.externalRef(), CLOUD_ID);

        return Optional.of(new RawFetchRequest(
                AuthHeaders.bearer(accessToken),
                projectKey,
                Map.of("baseUrl", baseUrl)
        ));
    }

    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant lastScannedAt = checkpointService.loadCursors(projectId, provider()).get(UPDATED_CURSOR);
        JiraRawService.JiraFetchContext context = rawService.prepareFetchContext(request, lastScannedAt);
        String nextPageToken = null;
        int totalPublished = 0;
        int pageNumber = 0;

        do {
            JiraRawService.JiraSearchPage page = rawService.fetchSearchPage(context, nextPageToken, pageNumber + 1);
            if (page.limitReached()) {
                log.warn("Jira max pages per run 도달: queued={}", totalPublished);
                break;
            }

            List<NormalizedEvent> pageEvents = normalizer.normalizeIssues(projectId, page.searchResult());

            totalPublished += eventPublisher.publishAll(pageEvents);
            CursorProgress.maxOccurredAt(pageEvents).ifPresent(cursor ->
                    checkpointService.updateCursor(projectId, provider(), UPDATED_CURSOR, cursor));

            nextPageToken = page.nextPageToken();
            pageNumber++;
        } while (nextPageToken != null && !nextPageToken.isBlank());

        log.info("Jira 이벤트 발행: {}", totalPublished);

        return totalPublished;
    }

    // OAuth 전환 후 Jira credential은 JSON({access_token, refresh_token, expires_at})이다.
    // Jackson 예외를 그대로 던지면 호출부의 안전망(IllegalArgumentException|IllegalStateException만 잡음)을
    // 우회해 잘못된 연동이 webhook 수집 전체를 실패시키므로 반드시 IllegalStateException으로 감싼다.
    private Map<String, Object> parseCredential(String json) {
        try {
            return objectMapper.readValue(json, CREDENTIAL_JSON_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Jira credential JSON.", exception);
        }
    }

    // access_token은 external_ref가 아니라 암호화된 credential JSON 안에 있어 requiredString과 메시지를
    // 분리한다 — 재사용하면 "Missing external_ref value: access_token"처럼 엉뚱한 위치를 가리켜 오도한다.
    // 패키지 접근으로 열어 테스트에서 메시지를 직접 검증한다.
    String requiredCredentialString(Map<String, Object> credential, String key) {
        Object value = credential.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing Jira credential field: " + key);
    }

    private String requiredString(Map<String, Object> externalRef, String key) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing external_ref value: " + key);
    }
}
