package com.history.pipeline_worker.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 운영자에게 Slack Incoming Webhook으로 알림을 보낸다. URL이 비면 ERROR 로그만 남긴다.
 * 같은 kind는 1시간에 한 번만 보낸다 — 억제 상태가 메모리라 재시작하면 한 번 더 갈 수 있다.
 * 전송은 동기(연결 3초·응답 5초)이며 실패해도 예외를 던지지 않아 호출부(릴레이·점검)를 깨지 않는다.
 */
@Slf4j
@Component
public class OperatorAlert {

    private static final Duration SUPPRESS_WINDOW = Duration.ofHours(1);
    private static final String PREFIX = "[pipeline-worker] ";

    private final RestClient restClient;
    private final String webhookUrl;
    private final Clock clock;
    private final Map<String, Instant> lastDelivered = new ConcurrentHashMap<>();

    public OperatorAlert(
            @Qualifier("alertRestClient") RestClient alertRestClient,
            @Value("${app.alert.slack-webhook-url:}") String webhookUrl,
            Clock clock) {
        this.restClient = alertRestClient;
        this.webhookUrl = webhookUrl;
        this.clock = clock;
    }

    public void notify(String kind, String text) {
        Instant now = clock.instant();
        Instant last = lastDelivered.get(kind);
        if (last != null && Duration.between(last, now).compareTo(SUPPRESS_WINDOW) < 0) {
            return;
        }

        log.error("ALERT({}): {}{}", kind, PREFIX, text);

        if (webhookUrl == null || webhookUrl.isBlank()) {
            lastDelivered.put(kind, now);
            return;
        }

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .body(Map.of("text", PREFIX + text))
                    .retrieve()
                    .toBodilessEntity();
            lastDelivered.put(kind, now);
        } catch (RestClientResponseException exception) {
            // 예외 메시지에 웹훅 URL(시크릿)이 들어가므로 타입·상태 코드만 남긴다.
            log.warn("ALERT({}) 전송 실패: {} status={}", kind, exception.getClass().getSimpleName(),
                    exception.getStatusCode().value());
        } catch (RestClientException exception) {
            log.warn("ALERT({}) 전송 실패: {}", kind, exception.getClass().getSimpleName());
        }
    }
}
