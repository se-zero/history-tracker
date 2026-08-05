package com.history.pipeline_worker.source.slack;

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
public class SlackCollector implements SourceCollector {

    static final String MESSAGES_CURSOR = "slack_messages";

    private final SlackRawService rawService;
    private final SlackNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final CredentialCryptoService credentialCryptoService;

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.SLACK;
    }

    // Slack은 전체 채널을 자동 수집하므로 external_ref에서 읽을 수집 대상이 없다.
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        if (integration.encryptedCredential() == null) {
            throw new IllegalStateException("Missing encrypted credential for provider: " + integration.provider());
        }
        String token = credentialCryptoService.decrypt(integration.encryptedCredential());
        return Optional.of(new RawFetchRequest(AuthHeaders.bearer(token), null, Map.of()));
    }

    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant lastScannedAt = checkpointService.loadCursors(projectId, provider()).get(MESSAGES_CURSOR);
        SlackRawService.SlackFetchContext context = rawService.prepareFetchContext(request, lastScannedAt);
        int published = 0;
        Instant cursor = null;

        for (Object rawChannel : rawService.fetchChannels(context)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) rawChannel;
            String pageCursor = null;
            do {
                SlackRawService.SlackHistoryPage page = rawService.fetchHistoryPage(context, channel, pageCursor);
                List<NormalizedEvent> pageEvents = normalizer.normalizeChannel(projectId, page.channelData());
                published += eventPublisher.publishAll(pageEvents);
                cursor = CursorProgress.later(cursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));
                pageCursor = page.nextCursor();
            } while (pageCursor != null && !pageCursor.isBlank());
        }

        // 채널을 가로질러 한 번만 전진시킨다 — 채널별로 갱신하면 늦은 채널이 이른 채널의 커서를 덮는다.
        checkpointService.updateCursor(projectId, provider(), MESSAGES_CURSOR, cursor);
        log.info("Slack 이벤트 발행: {}", published);

        return published;
    }
}
