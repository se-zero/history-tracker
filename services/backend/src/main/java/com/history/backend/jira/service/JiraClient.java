package com.history.backend.jira.service;

import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.dto.JiraProjectSearchResponse;
import com.history.backend.jira.dto.JiraUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Jira REST API 클라이언트 (cloudId 게이트웨이 경유, OAuth access token 기반)
@Slf4j
@Component
public class JiraClient {

    private final AtlassianProperties properties;
    private final RestClient restClient;

    public JiraClient(
            AtlassianProperties properties,
            @Qualifier("jiraRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // 사용자가 고를 수 있는 프로젝트 목록 조회
    public List<JiraProject> listProjects(String cloudId, String accessToken) {
        JiraProjectSearchResponse response;
        try {
            response = restClient
                    .get()
                    .uri(properties.apiGatewayUrl() + "/{cloudId}/rest/api/3/project/search?maxResults=100", cloudId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JiraProjectSearchResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("Jira project list request failed. status={} cloudId={}", exception.getStatusCode(), cloudId);
            throw new UnauthorizedException("Invalid Jira access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira project list request failed.", exception);
        }

        if (response == null || response.values() == null) {
            throw new BadGatewayException("Jira project list response is missing values.");
        }
        return response.values().stream()
                .map(value -> new JiraProject(value.key(), value.name()))
                .toList();
    }

    // 담당자 재조회 (개인정보 보고 배치의 access_lost 판정용). 429·5xx·연결 오류는 Jira 측 일시
    // 장애일 뿐 계정 삭제가 아니므로 BadGatewayException으로 넘긴다 — 여기서 Unauthorized로
    // 오판하면 배치가 "재조회 불가"를 "계정 접근 상실"로 오해해 아직 유효한 계정 매핑을 지운다.
    // listProjects는 상태 불문 Unauthorized로 처리하지만(별개 결함, 이번에 손대지 않음) 여기서는
    // 의도적으로 따르지 않는다.
    public JiraUser getUser(String cloudId, String accountId, String accessToken) {
        JiraUserResponse response;
        try {
            response = restClient
                    .get()
                    .uri(properties.apiGatewayUrl() + "/{cloudId}/rest/api/3/user?accountId={accountId}",
                            cloudId, accountId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JiraUserResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("Jira user lookup request failed.", exception);
            }
            log.warn("Jira user lookup rejected. status={} cloudId={}", exception.getStatusCode(), cloudId);
            throw new UnauthorizedException("Jira user lookup failed.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Jira user lookup request failed.", exception);
        }

        if (response == null) {
            throw new BadGatewayException("Jira user lookup response is empty.");
        }
        return new JiraUser(response.accountId(), response.displayName(), response.emailAddress());
    }

    private static boolean isTransientFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.TOO_MANY_REQUESTS) || status.is5xxServerError();
    }

    public record JiraProject(String key, String name) {
    }

    public record JiraUser(String accountId, String displayName, String emailAddress) {
    }
}
