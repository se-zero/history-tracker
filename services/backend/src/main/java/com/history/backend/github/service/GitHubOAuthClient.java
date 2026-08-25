package com.history.backend.github.service;

import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// GitHub OAuth 사용자 인증 API 클라이언트
@Slf4j
@Component
public class GitHubOAuthClient {

    private final GitHubAppProperties properties;
    private final RestClient restClient;

    // GitHubAppClient와 동일한 gitHubRestClient 빈을 쓴다 — 자체 생성 RestClient에는 타임아웃이
    // 없어 GitHub이 응답하지 않으면 로그인 요청이 Tomcat 스레드를 무기한 점유했다.
    public GitHubOAuthClient(GitHubAppProperties properties, @Qualifier("gitHubRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
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

    // 사용자 access token으로 installation 접근 권한 확인.
    // /user/installations/{id}/repositories는 인증된 사용자가 접근 권한을 가진 경우에만 200을 주고,
    // 권한이 없으면 403/404를 준다. 한 설치의 검증 실패로 로그인 전체가 막히면 안 되므로
    // 예외는 밖으로 던지지 않고 접근 불가로 처리한다.
    public boolean canAccessInstallation(String userAccessToken, Long installationId) {
        try {
            restClient
                    .get()
                    .uri(properties.userInstallationRepositoriesUrl(), installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            if (statusCode != HttpStatus.FORBIDDEN.value() && statusCode != HttpStatus.NOT_FOUND.value()) {
                log.warn("GitHub installation access check failed for installation {}: {}",
                        installationId, exception.getStatusCode());
            }
            return false;
        } catch (RestClientException exception) {
            log.warn("GitHub installation access check failed for installation {}", installationId, exception);
            return false;
        }
    }
}
