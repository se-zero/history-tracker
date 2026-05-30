package com.history.backend.github.service;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubInstallationTokenResponse;
import com.history.backend.github.dto.GitHubRepositoriesResponse;
import com.history.backend.github.dto.GitHubRepositoryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GitHubAppClient {

    private static final int REPOSITORIES_PER_PAGE = 100;

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
