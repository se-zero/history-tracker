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
import org.springframework.web.util.UriComponentsBuilder;

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
                .uri(fetchInstallationsUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .body(GitHubInstallationsResponse.class);
    }

    // 기본 30개로는 31번째 설치부터 응답에서 누락돼 pruneMemberships가 멀쩡한 멤버십을 지운다.
    // Link 헤더 기반 다음 페이지 추적은 이번 범위 밖 — 100개를 넘는 사용자가 생기면 추가한다.
    private String fetchInstallationsUri() {
        return UriComponentsBuilder.fromUriString(properties.installationsUrl())
                .queryParam("per_page", 100)
                .build()
                .toUriString();
    }

    // 사용자 access token으로 installation 접근 권한 확인.
    // /user/installations/{id}/repositories는 인증된 사용자가 접근 권한을 가진 경우에만 200을 주고,
    // 권한이 없으면 403/404를 준다. 그 외 상태(5xx 등)나 네트워크 예외는 "접근 없음"과 구분해야
    // 한다 — GitHub 부분 장애를 접근 거부로 오판하면 멀쩡한 멤버십이 prune 대상이 된다.
    public InstallationAccess checkInstallationAccess(String userAccessToken, Long installationId) {
        try {
            restClient
                    .get()
                    .uri(properties.userInstallationRepositoriesUrl(), installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .toBodilessEntity();
            return InstallationAccess.ACCESSIBLE;
        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            if (statusCode == HttpStatus.FORBIDDEN.value() || statusCode == HttpStatus.NOT_FOUND.value()) {
                return InstallationAccess.DENIED;
            }
            log.warn("GitHub installation access check failed for installation {}: {}",
                    installationId, exception.getStatusCode());
            return InstallationAccess.UNKNOWN;
        } catch (RestClientException exception) {
            log.warn("GitHub installation access check failed for installation {}", installationId, exception);
            return InstallationAccess.UNKNOWN;
        }
    }

    // installation 접근 판정 3상태 — DENIED만 진짜 접근 없음이고, UNKNOWN은 판단 보류(장애 등)다.
    // 둘을 boolean으로 합치면 장애 상황에서 멀쩡한 멤버십이 지워지는 회귀가 생긴다.
    public enum InstallationAccess {
        ACCESSIBLE,
        DENIED,
        UNKNOWN
    }
}
