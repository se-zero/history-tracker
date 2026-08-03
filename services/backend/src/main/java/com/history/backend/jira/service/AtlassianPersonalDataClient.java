package com.history.backend.jira.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianPersonalDataReportProperties;
import com.history.backend.jira.dto.AtlassianPersonalDataReportRequest;
import com.history.backend.jira.dto.AtlassianPersonalDataReportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Jira 개인정보 보고(personal data reporting) API 클라이언트 — 봇 계정 access token으로
// 계정 삭제·갱신 여부를 Atlassian에 보고받는다. 이 결과가 로컬 액터 매핑의 access_lost 판정에 쓰인다.
@Slf4j
@Component
public class AtlassianPersonalDataClient {

    private final AtlassianPersonalDataReportProperties properties;
    private final RestClient restClient;

    public AtlassianPersonalDataClient(
            AtlassianPersonalDataReportProperties properties,
            @Qualifier("jiraRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public ReportOutcome report(List<ReportAccount> accounts, String accessToken) {
        // updatedAt을 여기서 ISO_INSTANT로 문자열화해 요청 본문을 만든다 — Jackson 기본 직렬화 설정이
        // 바뀌어도(예: WRITE_DATES_AS_TIMESTAMPS) 요청 포맷이 흔들리지 않는다.
        List<AtlassianPersonalDataReportRequest.Account> requestAccounts = accounts.stream()
                .map(account -> new AtlassianPersonalDataReportRequest.Account(
                        account.accountId(), DateTimeFormatter.ISO_INSTANT.format(account.updatedAt())))
                .toList();

        // 응답의 Cycle-Period 헤더는 읽지 않는다 — 사전 스파이크에서 3LO(개인 access token) 응답에는
        // 이 헤더가 내려오지 않음을 확인했다.
        ResponseEntity<AtlassianPersonalDataReportResponse> response;
        try {
            response = restClient
                    .post()
                    .uri(properties.reportUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AtlassianPersonalDataReportRequest(requestAccounts))
                    .retrieve()
                    .toEntity(AtlassianPersonalDataReportResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().equals(HttpStatus.TOO_MANY_REQUESTS)) {
                // 일 1회 크론이라 재시도 스케줄을 따로 잡지 않는다 — 다음 회차에 자연히 재시도되므로
                // Retry-After는 로그로만 남긴다.
                log.warn("Atlassian personal data report rate limited. retryAfter={}",
                        exception.getResponseHeaders() == null
                                ? null : exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                throw new RateLimitedException();
            }
            if (exception.getStatusCode().is5xxServerError()) {
                throw new BadGatewayException("Atlassian personal data report request failed.", exception);
            }
            throw new UnauthorizedException("Atlassian personal data report bot credential is invalid.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Atlassian personal data report request failed.", exception);
        }

        AtlassianPersonalDataReportResponse body = response.getBody();
        if (body == null || body.accounts() == null) {
            return new ReportOutcome(List.of(), List.of());
        }
        return classify(body);
    }

    private ReportOutcome classify(AtlassianPersonalDataReportResponse body) {
        List<String> closedAccountIds = new ArrayList<>();
        List<String> updatedAccountIds = new ArrayList<>();
        for (AtlassianPersonalDataReportResponse.Account account : body.accounts()) {
            if ("closed".equals(account.status())) {
                closedAccountIds.add(account.accountId());
            } else if ("updated".equals(account.status())) {
                updatedAccountIds.add(account.accountId());
            } else {
                log.warn("Atlassian personal data report returned unknown status. accountId={} status={}",
                        account.accountId(), account.status());
            }
        }
        return new ReportOutcome(closedAccountIds, updatedAccountIds);
    }

    public record ReportAccount(String accountId, Instant updatedAt) {
    }

    public record ReportOutcome(List<String> closedAccountIds, List<String> updatedAccountIds) {
    }

    // 봇 토큰 rate limit — 자격증명 문제가 아니라 이번 회차만 건너뛰면 되므로 별도 예외로 분리
    public static class RateLimitedException extends RuntimeException {
    }
}
