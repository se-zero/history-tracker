package com.history.pipeline_worker.source.notion;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionCollector implements SourceCollector {

    static final String PAGES_CURSOR = "notion_pages";

    private static final String ACCESS_TOKEN = "access_token";
    private static final TypeReference<Map<String, Object>> CREDENTIAL_JSON_TYPE = new TypeReference<>() {
    };

    private final NotionRawService rawService;
    private final NotionNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper;

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.NOTION;
    }

    // Notion OAuth credential은 JSON({access_token, refresh_token})이다 — refresh_token은 지금
    // 쓰이지 않지만(갱신 미구현) 자리는 함께 저장돼 있다. 만료 판정은 하지 않는다 — Notion 갱신
    // 응답에 만료 정보가 없어 비만료 취급한다(Discord형). 선택 단계가 없어 external_ref는 읽지
    // 않는다 — 수집 범위가 토큰에 암시돼 있다(Slack과 같다).
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        Map<String, Object> credential = parseCredential(credentialCryptoService.decrypt(integration.encryptedCredential()));
        String accessToken = requiredCredentialString(credential, ACCESS_TOKEN);
        return Optional.of(new RawFetchRequest(AuthHeaders.bearer(accessToken), null, Map.of()));
    }

    /**
     * search는 {@code last_edited_time} 내림차순으로만 증분이 성립한다(§5-2) — 페이지 단위로
     * checkpoint를 전진시키면 아직 안 읽은 과거분이 checkpoint보다 오래된 것으로 읽혀 다음 수집에서
     * 영구 스킵된다. 그래서 발행은 배치마다 하되, checkpoint 전진은 실행 끝에 그 실행에서 본 최대
     * occurredAt으로 딱 한 번만 한다.
     */
    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant checkpoint = checkpointService.loadCursors(projectId, provider()).get(PAGES_CURSOR);
        NotionRawService.NotionFetchContext context = rawService.prepareFetchContext(request, checkpoint);
        // 워크스페이스 전체 사용자 맵 — partial user(id만)인 created_by/last_edited_by를 이름·이메일로
        // 보강하는 유일한 수단이다(§8). 실행당 한 번만 받되, 처리할 문서가 실제로 나온 뒤에 받는다:
        // webhook마다 도는 실행 중 Notion 변경이 0건인 쪽이 대부분인데, 미리 받으면 쓸 일 없는
        // 구성원 이름·이메일을 그때마다 메모리에 올리게 된다.
        Map<String, NotionRawService.NotionUser> users = null;

        int totalPublished = 0;
        Instant cursor = null;
        String searchCursor = null;

        do {
            NotionRawService.NotionSearchPageResult searchPage = rawService.searchPages(context, searchCursor);

            List<NormalizedEvent> pageEvents = new ArrayList<>();
            for (Map<String, Object> page : searchPage.pages()) {
                if (!(page.get("id") instanceof String pageId)) {
                    continue;
                }
                if (users == null) {
                    users = rawService.fetchAllUsers(context.auth());
                }
                String body = rawService.fetchPageBody(context, pageId);
                NormalizedEvent event = normalizer.normalizePage(projectId, page, body, users);
                if (event != null) {
                    pageEvents.add(event);
                }
            }

            totalPublished += eventPublisher.publishAll(pageEvents);
            cursor = CursorProgress.later(cursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));
            searchCursor = searchPage.nextCursor();
        } while (searchCursor != null);

        checkpointService.updateCursor(projectId, provider(), PAGES_CURSOR, cursor);
        log.info("Notion 이벤트 발행: {}", totalPublished);

        return totalPublished;
    }

    // Asana/ClickUp과 동일하게 Jackson 예외를 IllegalStateException으로 감싼다 — 그대로 던지면
    // 호출부의 안전망(IllegalArgumentException|IllegalStateException만 잡음)을 우회한다.
    private Map<String, Object> parseCredential(String json) {
        try {
            return objectMapper.readValue(json, CREDENTIAL_JSON_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Notion credential JSON.", exception);
        }
    }

    private String requiredCredentialString(Map<String, Object> credential, String key) {
        Object value = credential.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Missing Notion credential field: " + key);
    }
}
