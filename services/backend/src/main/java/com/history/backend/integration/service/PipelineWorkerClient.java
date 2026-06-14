package com.history.backend.integration.service;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class PipelineWorkerClient {

    private final RestClient restClient;

    public PipelineWorkerClient(
            @Qualifier("pipelineWorkerRestClient")
            RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public void triggerGitHubCollection(UUID projectId) {
        triggerCollection("github", projectId);
    }

    public void triggerJiraCollection(UUID projectId) {
        triggerCollection("jira", projectId);
    }

    public void triggerSlackCollection(UUID projectId) {
        triggerCollection("slack", projectId);
    }

    // 초기 수집 실패가 이미 완료된 provider 연동을 롤백시키지 않도록 요청 오류를 전파하지 않는다.
    private void triggerCollection(String provider, UUID projectId) {
        try {
            restClient.post()
                    .uri("/api/v1/collect/{provider}", provider)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CollectionTriggerRequest(projectId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn(
                    "pipeline-worker collection trigger failed. provider={}, projectId={}, error={}",
                    provider,
                    projectId,
                    exception.getMessage()
            );
        }
    }

    private record CollectionTriggerRequest(UUID projectId) {
    }
}
