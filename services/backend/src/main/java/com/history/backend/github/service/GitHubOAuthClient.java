package com.history.backend.github.service;

import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

// GitHub OAuth 사용자 인증 API 클라이언트
@Component
public class GitHubOAuthClient {

    private final GitHubAppProperties properties;
    private final RestClient restClient;

    public GitHubOAuthClient(GitHubAppProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    public GitHubAccessTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());

        GitHubAccessTokenResponse response = restClient
                .post()
                .uri(properties.accessTokenUrl())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GitHubAccessTokenResponse.class);

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new UnauthorizedException("Invalid GitHub authorization code.");
        }

        return response;
    }

    public GitHubUserResponse fetchUser(String accessToken) {
        return restClient
                .get()
                .uri(properties.userUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .body(GitHubUserResponse.class);
    }

    public GitHubInstallationsResponse fetchInstallations(String accessToken) {
        return restClient
                .get()
                .uri(properties.installationsUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .body(GitHubInstallationsResponse.class);
    }
}
