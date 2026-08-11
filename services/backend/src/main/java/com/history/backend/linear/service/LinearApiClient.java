package com.history.backend.linear.service;

import java.util.List;
import java.util.Map;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.linear.dto.LinearTeamsQueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Linear REST가 아니라 단일 GraphQL 엔드포인트를 쓰는 API 클라이언트 (OAuth access token 기반).
// 오류도 HTTP 200으로 내려주므로 응답 바디의 errors 배열을 별도로 확인해야 한다
// (그렇지 않으면 실패를 성공으로 오인한다).
@Slf4j
@Component
public class LinearApiClient {

    private static final String GRAPHQL_URL = "https://api.linear.app/graphql";
    // 알려진 한계: 첫 페이지(Linear 기본 50개)만 조회한다 — 팀이 50개를 넘는 워크스페이스에서는
    // 뒷 팀이 선택 후보에 나오지 않는다. 필요해지면 first/after 커서 순회를 추가한다.
    private static final String TEAMS_QUERY = """
            query {
              teams {
                nodes {
                  id
                  name
                }
              }
            }
            """;

    private final RestClient restClient;

    public LinearApiClient(@Qualifier("linearRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 사용자가 고를 수 있는 team 목록 조회
    public List<LinearTeam> listTeams(String accessToken) {
        LinearTeamsQueryResponse response;
        try {
            response = restClient
                    .post()
                    .uri(GRAPHQL_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", TEAMS_QUERY))
                    .retrieve()
                    .body(LinearTeamsQueryResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("Linear team list request failed.", exception);
            }
            log.warn("Linear team list request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid Linear access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Linear team list request failed.", exception);
        }

        if (response == null) {
            throw new BadGatewayException("Linear team list response is empty.");
        }
        if (response.errors() != null && !response.errors().isEmpty()) {
            throw new BadGatewayException("Linear GraphQL response contains errors: " + response.errors());
        }
        if (response.data() == null || response.data().teams() == null || response.data().teams().nodes() == null) {
            throw new BadGatewayException("Linear team list response is missing nodes.");
        }
        return response.data().teams().nodes().stream()
                .map(node -> new LinearTeam(node.id(), node.name()))
                .toList();
    }

    private static boolean isTransientFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.TOO_MANY_REQUESTS) || status.is5xxServerError();
    }

    public record LinearTeam(String id, String name) {
    }
}
