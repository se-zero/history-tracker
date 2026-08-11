package com.history.pipeline_worker.collection;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// backend에 provider access token 확보(필요 시 갱신)를 요청한다. ProjectIntegrationService와 같은
// 패키지에 두는 이유는 이 호출이 "자격증명을 쓸 수 있게 만드는" 일이기 때문이다.
// JiraTokenClient를 provider 파라미터화한 후신 — Jira 전용이던 endpoint를 provider 인자로 일반화했다.
@Component
public class IntegrationTokenClient {

    private static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String internalServiceToken;

    public IntegrationTokenClient(
            @Qualifier("backendRestClient") RestClient restClient,
            @Value("${security.internal-service.token}") String internalServiceToken
    ) {
        this.restClient = restClient;
        this.internalServiceToken = internalServiceToken;
    }

    public boolean ensureToken(UUID projectId, CollectionProvider provider) {
        try {
            restClient.post()
                    .uri("/api/v1/internal/integrations/{projectId}/{provider}/token", projectId, provider.value())
                    .header(INTERNAL_SERVICE_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }
}
