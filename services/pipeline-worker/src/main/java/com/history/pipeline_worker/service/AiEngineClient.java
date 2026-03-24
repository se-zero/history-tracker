package com.history.pipeline_worker.service;

import com.history.pipeline_worker.dto.NormalizedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

// 정규화된 이벤트를 ai-engine으로 HTTP 전달
// ai-engine이 아직 미구현 상태이므로 실패 시 로그만 남기고 계속 진행
@Slf4j
@Service
public class AiEngineClient {

    private final WebClient webClient;

    public AiEngineClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai-engine.base-url}") String baseUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public void ingest(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) return;

        try {
            webClient.post()
                    .uri("/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(events)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("ai-engine ingest 완료: {}건", events.size());
        } catch (Exception e) {
            // ai-engine 미구현 단계 — 연결 실패는 무시하고 로그만 남김
            log.warn("ai-engine ingest 실패 (ai-engine 미구현 상태일 수 있음): {}", e.getMessage());
        }
    }
}
