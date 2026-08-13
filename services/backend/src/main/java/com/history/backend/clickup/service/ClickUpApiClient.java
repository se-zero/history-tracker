package com.history.backend.clickup.service;

import java.util.List;

import com.history.backend.clickup.dto.ClickUpFolderListResponse;
import com.history.backend.clickup.dto.ClickUpListsResponse;
import com.history.backend.clickup.dto.ClickUpSpaceListResponse;
import com.history.backend.clickup.dto.ClickUpTeamListResponse;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// ClickUp REST API 클라이언트 (OAuth access token 기반). 계층 조회 4단(team → space → folder → list)
// 이고, folder는 건너뛸 수 있어 space에서 바로(folderless) list를 조회하는 경로도 있다. 응답은
// Asana와 달리 엔드포인트마다 래핑 키가 다르다("teams"/"spaces"/"folders"/"lists").
@Slf4j
@Component
public class ClickUpApiClient {

    private static final String BASE_URL = "https://api.clickup.com/api/v2";

    private final RestClient restClient;

    public ClickUpApiClient(@Qualifier("clickUpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 사용자가 고를 수 있는 workspace(team) 목록 조회
    public List<ClickUpWorkspace> listWorkspaces(String accessToken) {
        ClickUpTeamListResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/team")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(ClickUpTeamListResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("ClickUp workspace list request failed.", exception);
            }
            log.warn("ClickUp workspace list request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid ClickUp access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("ClickUp workspace list request failed.", exception);
        }

        if (response == null || response.teams() == null) {
            throw new BadGatewayException("ClickUp workspace list response is missing data.");
        }
        return response.teams().stream()
                .map(item -> new ClickUpWorkspace(item.id(), item.name()))
                .toList();
    }

    // 선택된 workspace 안에서 사용자가 고를 수 있는 space 목록 조회
    public List<ClickUpSpace> listSpaces(String workspaceId, String accessToken) {
        ClickUpSpaceListResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/team/{workspaceId}/space", workspaceId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(ClickUpSpaceListResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("ClickUp space list request failed.", exception);
            }
            log.warn("ClickUp space list request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid ClickUp access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("ClickUp space list request failed.", exception);
        }

        if (response == null || response.spaces() == null) {
            throw new BadGatewayException("ClickUp space list response is missing data.");
        }
        return response.spaces().stream()
                .map(item -> new ClickUpSpace(item.id(), item.name()))
                .toList();
    }

    // 선택된 space 안에서 사용자가 고를 수 있는 folder 목록 조회
    public List<ClickUpFolder> listFolders(String spaceId, String accessToken) {
        ClickUpFolderListResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/space/{spaceId}/folder", spaceId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(ClickUpFolderListResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("ClickUp folder list request failed.", exception);
            }
            log.warn("ClickUp folder list request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid ClickUp access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("ClickUp folder list request failed.", exception);
        }

        if (response == null || response.folders() == null) {
            throw new BadGatewayException("ClickUp folder list response is missing data.");
        }
        return response.folders().stream()
                .map(item -> new ClickUpFolder(item.id(), item.name()))
                .toList();
    }

    // 선택된 folder 안의 list 목록 조회
    public List<ClickUpList> listFolderLists(String folderId, String accessToken) {
        ClickUpListsResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/folder/{folderId}/list", folderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(ClickUpListsResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("ClickUp folder list-of-lists request failed.", exception);
            }
            log.warn("ClickUp folder list-of-lists request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid ClickUp access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("ClickUp folder list-of-lists request failed.", exception);
        }

        if (response == null || response.lists() == null) {
            throw new BadGatewayException("ClickUp folder list-of-lists response is missing data.");
        }
        return response.lists().stream()
                .map(item -> new ClickUpList(item.id(), item.name()))
                .toList();
    }

    // 폴더 없는(folderless) space 안의 list 목록 조회 — folder 단계를 건너뛴 경우
    public List<ClickUpList> listFolderlessLists(String spaceId, String accessToken) {
        ClickUpListsResponse response;
        try {
            response = restClient
                    .get()
                    .uri(BASE_URL + "/space/{spaceId}/list", spaceId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(ClickUpListsResponse.class);
        } catch (RestClientResponseException exception) {
            if (isTransientFailure(exception)) {
                throw new BadGatewayException("ClickUp folderless list-of-lists request failed.", exception);
            }
            log.warn("ClickUp folderless list-of-lists request failed. status={}", exception.getStatusCode());
            throw new UnauthorizedException("Invalid ClickUp access token.");
        } catch (RestClientException exception) {
            throw new BadGatewayException("ClickUp folderless list-of-lists request failed.", exception);
        }

        if (response == null || response.lists() == null) {
            throw new BadGatewayException("ClickUp folderless list-of-lists response is missing data.");
        }
        return response.lists().stream()
                .map(item -> new ClickUpList(item.id(), item.name()))
                .toList();
    }

    private static boolean isTransientFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.TOO_MANY_REQUESTS) || status.is5xxServerError();
    }

    public record ClickUpWorkspace(String id, String name) {
    }

    public record ClickUpSpace(String id, String name) {
    }

    public record ClickUpFolder(String id, String name) {
    }

    public record ClickUpList(String id, String name) {
    }
}
