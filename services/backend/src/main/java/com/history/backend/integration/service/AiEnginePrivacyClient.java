package com.history.backend.integration.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.integration.dto.EraseAccount;
import com.history.backend.integration.dto.PrivacyApplyResult;
import com.history.backend.integration.dto.PrivacyDueAccount;
import com.history.backend.integration.dto.RefreshAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// Jira 개인정보 보고 배치가 ai-engine의 개인정보 엔드포인트(app 전역, project_id 무관)를 호출하는 클라이언트.
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEnginePrivacyClient {

    private final RestClient aiEngineRestClient;

    public List<PrivacyDueAccount> fetchDueAccounts(Instant dueBefore, int limit) {
        try {
            List<PrivacyDueAccount> response = aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/privacy/jira/accounts")
                            .queryParam("due_before", "{dueBefore}")
                            .queryParam("limit", "{limit}")
                            .build(DateTimeFormatter.ISO_INSTANT.format(dueBefore), limit))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PrivacyDueAccount>>() {
                    });
            return response == null ? List.of() : response;
        } catch (RestClientException exception) {
            log.error("ai-engine privacy due accounts request failed: {}", exception.getMessage());
            throw new BadGatewayException("Failed to load privacy due accounts.");
        }
    }

    public PrivacyApplyResult applyReport(
            Instant reportedAt, List<String> ok, List<EraseAccount> erase, List<RefreshAccount> refresh) {
        try {
            PrivacyApplyResult response = aiEngineRestClient.post()
                    .uri("/privacy/jira/accounts/apply")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApplyReportRequest(
                            DateTimeFormatter.ISO_INSTANT.format(reportedAt), ok, erase, refresh))
                    .retrieve()
                    .body(PrivacyApplyResult.class);
            if (response == null) {
                throw new BadGatewayException("Failed to apply privacy report.");
            }
            return response;
        } catch (RestClientException exception) {
            log.error("ai-engine privacy apply request failed: {}", exception.getMessage());
            throw new BadGatewayException("Failed to apply privacy report.");
        }
    }

    public int verifyActorNames() {
        try {
            VerifyActorNamesResponse response = aiEngineRestClient.post()
                    .uri("/migrations/verify-actor-names")
                    .retrieve()
                    .body(VerifyActorNamesResponse.class);
            return response == null || response.mismatches() == null ? 0 : response.mismatches().size();
        } catch (RestClientException exception) {
            log.error("ai-engine actor name verification request failed: {}", exception.getMessage());
            throw new BadGatewayException("Failed to verify actor names.");
        }
    }

    private record ApplyReportRequest(
            @JsonProperty("reported_at") String reportedAt,
            List<String> ok,
            List<EraseAccount> erase,
            List<RefreshAccount> refresh
    ) {
    }

    private record VerifyActorNamesResponse(List<Object> mismatches) {
    }
}
