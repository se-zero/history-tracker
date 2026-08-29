package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.security.InternalServiceAuthenticationFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class PipelineWorkerClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public PipelineWorkerClient(
            @Qualifier("pipelineWorkerRestClient")
            RestClient restClient,
            @Value("${security.internal-service.token}")
            String internalServiceToken
    ) {
        this.restClient = restClient;
        this.internalServiceToken = internalServiceToken;
    }

    // 초기 수집 실패가 이미 완료된 provider 연동을 롤백시키지 않도록 요청 오류를 전파하지 않는다.
    // 이 실패 흡수 때문에 헤더를 빠뜨리면 pipeline-worker가 401을 반환해도 여기서는 로그만 남고
    // 초기 수집이 조용히 안 돈다 — 그래서 헤더 전송 자체를 테스트로 고정한다.
    public void triggerCollection(IntegrationProvider provider, UUID projectId) {
        try {
            restClient.post()
                    .uri("/api/v1/collect/{provider}", provider.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(InternalServiceAuthenticationFilter.HEADER_NAME, internalServiceToken)
                    .body(new CollectionTriggerRequest(projectId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn(
                    "pipeline-worker collection trigger failed. provider={}, projectId={}, error={}",
                    provider.value(),
                    projectId,
                    exception.getMessage()
            );
        }
    }

    private record CollectionTriggerRequest(UUID projectId) {
    }
}
