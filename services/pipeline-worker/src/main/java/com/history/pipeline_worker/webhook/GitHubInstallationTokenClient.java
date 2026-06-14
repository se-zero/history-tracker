package com.history.pipeline_worker.webhook;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GitHubInstallationTokenClient {

    private static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String internalServiceToken;

    public GitHubInstallationTokenClient(
            @Qualifier("backendRestClient") RestClient restClient,
            @Value("${security.internal-service.token}") String internalServiceToken
    ) {
        this.restClient = restClient;
        this.internalServiceToken = internalServiceToken;
    }

    public boolean ensureInstallationToken(Long installationId) {
        try {
            restClient.post()
                    .uri("/api/v1/internal/github/installations/{installationId}/token", installationId)
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
