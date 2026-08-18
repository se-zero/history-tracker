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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        Map<String, Instant> cursors = checkpointService.loadCursors(projectId, provider());
        Instant legacyCursor = cursors.get(MESSAGES_CURSOR);
        SlackRawService.SlackFetchContext context = rawService.prepareFetchContext(request);
        int published = 0;

        List<Object> channels = rawService.fetchChannels(context);

        // 삭제·아카이브돼 목록에서 사라진 채널의 커서가 영구 잔류하는 것을 막는다. 목록 조회가
        // 성공한 직후라 목록이 가장 신선하고, 이후 수집이 실패해도 고아 삭제는 무해하다.
        Set<String> currentChannelIds = new HashSet<>();
        for (Object rawChannel : channels) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) rawChannel;
            currentChannelIds.add((String) channel.get("id"));
        }
        for (String cursorKey : cursors.keySet()) {
            if (!cursorKey.startsWith(MESSAGES_CURSOR + ":")) {
                continue;
            }
            String channelId = cursorKey.substring((MESSAGES_CURSOR + ":").length());
            if (!currentChannelIds.contains(channelId)) {
                checkpointService.deleteCursor(projectId, provider(), cursorKey);
            }
        }

        for (Object rawChannel : channels) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) rawChannel;
            String channelId = (String) channel.get("id");
            String channelKey = MESSAGES_CURSOR + ":" + channelId;
            Instant channelStart = cursors.getOrDefault(channelKey, legacyCursor);
            Instant channelCursor = channelStart;
            String pageCursor = null;
            do {
                SlackRawService.SlackHistoryPage page = rawService.fetchHistoryPage(context, channel, pageCursor, channelStart);
                List<NormalizedEvent> pageEvents = normalizer.normalizeChannel(projectId, page.channelData());
                published += eventPublisher.publishAll(pageEvents);
                channelCursor = CursorProgress.later(channelCursor, CursorProgress.maxOccurredAt(pageEvents).orElse(null));
                pageCursor = page.nextCursor();
            } while (pageCursor != null && !pageCursor.isBlank());

            // 채널별 키라 채널 간 덮어쓰기가 없다. 완주한 채널부터 저장해 중간에 죽어도 다음 실행이
            // 그 채널을 건너뛴다. history는 최신→과거 순이라 max occurredAt이 1페이지에 오므로
            // 페이지 단위 전진은 불가하다(중간에 죽으면 안 걸은 과거 구간이 영구 누락된다) —
            // 채널 완주가 안전한 최소 단위다.
            checkpointService.updateCursor(projectId, provider(), channelKey, channelCursor);
        }

        // 완주 시드 방식 덕에 이 시점엔 모든 채널이 자기 키를 가져 fallback이 더는 필요 없다.
        // 빈 목록 가드는 토큰 이상으로 빈 목록이 왔을 때 워터마크(전역 커서)를 날리는 사고 방지.
        if (legacyCursor != null && !channels.isEmpty()) {
            checkpointService.deleteCursor(projectId, provider(), MESSAGES_CURSOR);
        }

        log.info("Slack 이벤트 발행: {}", published);

        return published;
    }
}
