package com.history.backend.github.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubBranchResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.GitHubInstallationTokenResponse;
import com.history.backend.github.dto.GitHubRepositoriesResponse;
import com.history.backend.github.dto.GitHubRepositoryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

// GitHub App API 클라이언트 (App JWT / installation token 인증)
@Component
public class GitHubAppClient {

    private static final int REPOSITORIES_PER_PAGE = 100;
    private static final int BRANCHES_PER_PAGE = 100;

    private final GitHubAppProperties properties;
    private final GitHubAppJwtService gitHubAppJwtService;
    private final RestClient restClient;

    public GitHubAppClient(
            GitHubAppProperties properties,
            GitHubAppJwtService gitHubAppJwtService,
            @Qualifier("gitHubRestClient")
            RestClient restClient
    ) {
        this.properties = properties;
        this.gitHubAppJwtService = gitHubAppJwtService;
        this.restClient = restClient;
    }

    // installation access token 발급 요청
    public InstallationAccessToken createInstallationAccessToken(Long installationId) {
        GitHubInstallationTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.installationAccessTokenUrl(), installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + gitHubAppJwtService.createJwt())
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubInstallationTokenResponse.class);
        } catch (RestClientResponseException exception) {
            // GitHub 응답 오류·통신 실패는 외부 서비스 장애로 간주해 502로 변환
            throw gitHubApiException("GitHub installation access token request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub installation access token request failed.", exception);
        }

        if (response == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalStateException("GitHub installation access token response is empty.");
        }
        if (response.expiresAt() == null || response.expiresAt().isBlank()) {
            throw new IllegalStateException("GitHub installation access token expiry is empty.");
        }
        return new InstallationAccessToken(response.token(), parseExpiresAt(response.expiresAt()));
    }

    // 계정 단위(개인) installation 단건 조회. GET /user/installations는 저장소 접근 판정 기반이라
    // 저장소가 0개인 설치를 누락하므로, 목록에 없을 때의 폴백 확인 용도로 쓴다.
    public Optional<GitHubInstallationResponse> fetchUserInstallation(String username) {
        try {
            GitHubInstallationResponse response = restClient
                    .get()
                    .uri(properties.userInstallationUrl(), username)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + gitHubAppJwtService.createJwt())
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubInstallationResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientResponseException exception) {
            // 404는 해당 계정에 앱이 설치되지 않았다는 정상적인 판정이므로 예외로 취급하지 않는다.
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw gitHubApiException("GitHub user installation request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub user installation request failed.", exception);
        }
    }

    // 설치 단건 조회 (App JWT). 404는 "설치가 삭제/부재"라는 정상 판정 — fetchUserInstallation과 동일 계약.
    public Optional<GitHubInstallationResponse> fetchInstallation(Long installationId) {
        try {
            GitHubInstallationResponse response = restClient
                    .get()
                    .uri(properties.appInstallationUrl(), installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + gitHubAppJwtService.createJwt())
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubInstallationResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw gitHubApiException("GitHub app installation request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub app installation request failed.", exception);
        }
    }

    // 설치 저장소 전체 조회 (100개 단위 페이지네이션, 마지막 페이지까지 반복)
    public List<GitHubRepositoryResponse> fetchInstallationRepositories(String installationAccessToken) {
        List<GitHubRepositoryResponse> repositories = new ArrayList<>();
        int page = 1;
        while (true) {
            List<GitHubRepositoryResponse> pageRepositories = fetchInstallationRepositoryPage(
                    installationAccessToken,
                    page
            );
            repositories.addAll(pageRepositories);
            if (pageRepositories.size() < REPOSITORIES_PER_PAGE) {
                return repositories;
            }
            page++;
        }
    }

    private List<GitHubRepositoryResponse> fetchInstallationRepositoryPage(
            String installationAccessToken,
            int page
    ) {
        GitHubRepositoriesResponse response;
        try {
            response = restClient
                    .get()
                    .uri(repositoryPageUri(page))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GitHubRepositoriesResponse.class);
        } catch (RestClientResponseException exception) {
            throw gitHubApiException("GitHub repository list request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub repository list request failed.", exception);
        }

        if (response == null || response.repositories() == null) {
            return List.of();
        }
        return response.repositories();
    }

    private String repositoryPageUri(int page) {
        return UriComponentsBuilder.fromUriString(properties.installationRepositoriesUrl())
                .queryParam("per_page", REPOSITORIES_PER_PAGE)
                .queryParam("page", page)
                .build()
                .toUriString();
    }

    // 저장소 브랜치 전체 조회 (100개 단위 페이지네이션)
    public List<String> fetchRepositoryBranches(String installationAccessToken, String owner, String repo) {
        List<String> branches = new ArrayList<>();
        int page = 1;
        while (true) {
            List<GitHubBranchResponse> pageBranches = fetchRepositoryBranchPage(
                    installationAccessToken,
                    owner,
                    repo,
                    page
            );
            for (GitHubBranchResponse branch : pageBranches) {
                if (branch.name() != null) {
                    branches.add(branch.name());
                }
            }
            if (pageBranches.size() < BRANCHES_PER_PAGE) {
                return branches;
            }
            page++;
        }
    }

    private List<GitHubBranchResponse> fetchRepositoryBranchPage(
            String installationAccessToken,
            String owner,
            String repo,
            int page
    ) {
        GitHubBranchResponse[] response;
        try {
            response = restClient
                    .get()
                    .uri(branchPageUri(owner, repo, page))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GitHubBranchResponse[].class);
        } catch (RestClientResponseException exception) {
            throw gitHubApiException("GitHub branch list request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub branch list request failed.", exception);
        }

        return response == null ? List.of() : List.of(response);
    }

    private String branchPageUri(String owner, String repo, int page) {
        return UriComponentsBuilder.fromUriString(properties.repositoryBranchesUrl())
                .queryParam("per_page", BRANCHES_PER_PAGE)
                .queryParam("page", page)
                .buildAndExpand(owner, repo)
                .toUriString();
    }

    private BadGatewayException gitHubApiException(String message, RestClientResponseException exception) {
        return new BadGatewayException(message + " GitHub responded with " + exception.getStatusCode() + ".", exception);
    }

    private Instant parseExpiresAt(String expiresAt) {
        try {
            return Instant.parse(expiresAt);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("GitHub installation access token expiry is invalid.", exception);
        }
    }
}
