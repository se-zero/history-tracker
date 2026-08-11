package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.checkpoint.CursorProgress;
import com.history.pipeline_worker.collection.AuthHeaders;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.collection.SourceCollector;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class DiscordCollector implements SourceCollector {

    static final String MESSAGES_CURSOR = "discord_messages";
    private static final String GUILD_ID_KEY = "guild_id";

    private final DiscordRawService rawService;
    private final DiscordNormalizer normalizer;
    private final EventPublisher eventPublisher;
    private final CheckpointService checkpointService;
    private final String botToken;

    public DiscordCollector(
            DiscordRawService rawService,
            DiscordNormalizer normalizer,
            EventPublisher eventPublisher,
            CheckpointService checkpointService,
            @Value("${app.discord.bot-token}") String botToken
    ) {
        this.rawService = rawService;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.checkpointService = checkpointService;
        this.botToken = botToken;
    }

    @Override
    public CollectionProvider provider() {
        return CollectionProvider.DISCORD;
    }

    // 수집 주체는 앱 수준 봇이다 — 행의 사용자 OAuth 토큰(refresh token)은 해제 시 grant 폐기용이라
    // 여기서 복호화하지 않는다. 자격증명은 이 worker 설정의 봇 토큰뿐이다.
    @Override
    public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
        Object guildId = integration.externalRef().get(GUILD_ID_KEY);
        if (!(guildId instanceof String id) || id.isBlank()) {
            throw new IllegalStateException("Missing guild_id for provider: " + integration.provider());
        }
        return Optional.of(new RawFetchRequest(AuthHeaders.bot(botToken), id, Map.of()));
    }

    @Override
    public int collect(String projectId, RawFetchRequest request) {
        Instant lastScannedAt = checkpointService.loadCursors(projectId, provider()).get(MESSAGES_CURSOR);
        DiscordRawService.DiscordFetchContext context = rawService.prepareFetchContext(request, lastScannedAt);
        String guildId = request.projectKey();

        List<Map<String, Object>> channels;
        try {
            channels = rawService.fetchChannels(context);
        } catch (WebClientResponseException.Forbidden exception) {
            // 관리자가 연동 해제 없이 봇을 서버에서 추방한 경우 — 봇이 더는 길드 멤버가 아니라
            // 채널 목록 조회 자체가 403이다. 채널 단위 403과 같은 철학으로 이번 실행은 Discord만
            // 건너뛴다(checkpoint 미전진 — 봇이 재초대되면 그 사이 메시지를 놓치지 않고 다시 수집한다).
            // 여기서 삼키지 않으면 PipelineService.collectIncremental이 예외를 그대로 전파해, 선언
            // 순서상 Discord 바로 다음인 Google Chat까지 이 프로젝트의 매 웹훅마다 함께 실패한다.
            log.warn("Discord 길드 접근 권한 없음(봇이 추방됐을 수 있음) — 이번 실행은 건너뜀: guildId={}", guildId);
            return 0;
        }

        int published = 0;
        Instant cursor = null;

        for (Object rawChannel : channels) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) rawChannel;

            // 채널 전체를 모으지 않고 페이지마다 발행한다(Slack과 같은 이유) — 발행 배치와 메모리가
            // 채널 크기에 비례하면 큰 채널이 confirm 타임아웃에 걸려 재시도해도 계속 실패한다.
            String pageCursor = null;
            do {
                DiscordRawService.DiscordMessagePage page;
                try {
                    page = rawService.fetchMessagePage(context, channel, pageCursor);
                } catch (WebClientResponseException.Forbidden exception) {
                    // 봇이 View Channel·Read Message History 권한을 못 받은 채널 — 이 채널만 건너뛴다
                    log.warn("Discord 채널 접근 권한 없음 — 건너뜀: channelId={}", channel.get("id"));
                    break;
                }

                List<NormalizedEvent> events =
                        normalizer.normalizeChannel(projectId, guildId, channel, page.messages());
                published += eventPublisher.publishAll(events);
                cursor = CursorProgress.later(cursor, CursorProgress.maxOccurredAt(events).orElse(null));
                pageCursor = page.nextCursor();
            } while (pageCursor != null);
        }

        // 채널을 가로질러 한 번만 전진시킨다 — 채널별로 갱신하면 늦은 채널이 이른 채널의 커서를 덮는다.
        checkpointService.updateCursor(projectId, provider(), MESSAGES_CURSOR, cursor);
        log.info("Discord 이벤트 발행: {}", published);

        return published;
    }
}
