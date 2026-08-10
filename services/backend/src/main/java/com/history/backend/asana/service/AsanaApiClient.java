package com.history.backend.asana.service;

import java.util.List;

import com.history.backend.asana.dto.AsanaListResponse;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Asana REST API 클라이언트 (OAuth access token 기반). 베이스 URL은 provider 고정값이라
// properties가 아니라 클래스 상수로 둔다(Linear의 GraphQL 엔드포인트와 동일한 관행).
@Slf4j
@Component
public class AsanaApiClient {

    private static final String BASE_URL = "https://app.asana.com/api/1.0";

    private final RestClient restClient;

    public AsanaApiClient(@Qualifier("asanaRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 사용자가 고를 수 있는 workspace 목록 조회
    public List<AsanaWorkspace> listWorkspaces(String accessToken) {
        AsanaListResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/workspaces?limit=100")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(AsanaListResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("Asana workspace list request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid Asana access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Asana workspace list request failed.", exception);
        }

        if (response == null || response.data() == null) {
            throw new BadGatewayException("Asana workspace list response is missing data.");
        }
        return response.data().stream()
                .map(item -> new AsanaWorkspace(item.gid(), item.name()))
                .toList();
    }

    // 선택된 workspace 안에서 사용자가 고를 수 있는 project 목록 조회 (보관된 project는 제외)
    public List<AsanaProject> listProjects(String workspaceGid, String accessToken) {
        AsanaListResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/projects?workspace={workspaceGid}&archived=false&limit=100", workspaceGid)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(AsanaListResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("Asana project list request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid Asana access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("Asana project list request failed.", exception);
        }

        if (response == null || response.data() == null) {
            throw new BadGatewayException("Asana project list response is missing data.");
        }
        return response.data().stream()
                .map(item -> new AsanaProject(item.gid(), item.name()))
                .toList();
    }

    public record AsanaWorkspace(String gid, String name) {
    }

    public record AsanaProject(String gid, String name) {
    }
}
