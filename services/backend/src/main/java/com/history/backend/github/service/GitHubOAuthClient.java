package com.history.backend.github.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubBranchResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubRepositoriesResponse;
import com.history.backend.github.dto.GitHubRepositoryResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

    private static final int REPOSITORIES_PER_PAGE = 100;
    private static final int BRANCHES_PER_PAGE = 100;

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

        // Expire user tokens가 꺼진 앱은 refresh·expires_in·refresh_token_expires_in을 주지 않는다.
        // access만 통과시키면 로그인 직후 저장할 갱신 재료가 없어 이후 갱신이 영구히 실패하고,
        // refresh_token_expires_in만 빠지면 save가 plusSeconds(null)에서 NPE(500)가 난다.
        if (response == null
                || response.accessToken() == null || response.accessToken().isBlank()
                || response.refreshToken() == null || response.refreshToken().isBlank()
                || response.expiresIn() == null
                || response.refreshTokenExpiresIn() == null) {
            throw new UnauthorizedException("Invalid GitHub authorization code.");
        }

        return response;
    }

    // refresh token으로 access token 갱신. GitHub도 갱신할 때마다 새 refresh token을 함께 내려주고
    // 직전 것을 즉시 무효화한다 — 응답의 refreshToken()을 반드시 덮어써 저장해야 다음 갱신이 성공한다.
    public GitHubAccessTokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);

        GitHubAccessTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.accessTokenUrl())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GitHubAccessTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                // refresh token이 폐기됨(재동의 취소·만료) — 호출부가 이 예외를 보고 행을 지운다.
                // 폐기 판정은 400/401/403으로만 좁힌다: 429는 rate limit이지 폐기가 아니고,
                // 5xx는 GitHub 측 일시 장애다. 여기서 오판하면 아직 유효한 자격증명 행이 삭제된다.
                throw new UnauthorizedException("GitHub refresh token is invalid or revoked.");
            }
            throw new BadGatewayException("GitHub OAuth token refresh request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub OAuth token refresh request failed.", exception);
        }

        // GitHub는 이 엔드포인트에서 RFC와 달리 실패를 HTTP 200 + {"error":"..."} 로도 준다.
        // 4xx catch에 안 걸리면 아래 missing-field 분기가 502로 삼켜, 행 삭제·재로그인 안내가 안 탄다.
        if (isRevokedRefreshError(response)) {
            throw new UnauthorizedException("GitHub refresh token is invalid or revoked.");
        }

        // 회전 응답에서 필드가 빠지면 옛 refresh를 덮어쓸 값이 없다. 로그인 code 교환과 달리
        // 여기 실패는 사용자 입력이 아니라 GitHub 측 이상 응답이므로 401이 아니라 502다.
        if (response == null
                || response.accessToken() == null || response.accessToken().isBlank()
                || response.refreshToken() == null || response.refreshToken().isBlank()
                || response.expiresIn() == null
                || response.refreshTokenExpiresIn() == null) {
            throw new BadGatewayException("GitHub OAuth token refresh response is missing rotated tokens.");
        }

        return response;
    }

    /**
     * 사용자 GitHub App grant 폐기. DELETE에 JSON 바디가 필요해 {@code RestClient.delete()}가 아니라
     * {@code method(DELETE)}를 쓴다.
     *
     * <p>실패해도 예외를 던지지 않는다 — 이미 폐기됐거나 GitHub 장애일 때 사용자 파기 자체가 막히면
     * 안 된다. 404/401은 지울 대상이 없다는 뜻이라 성공으로 본다.</p>
     */
    public boolean revokeGrant(String accessToken) {
        String grantRevokeUri = UriComponentsBuilder.fromUriString(properties.grantRevokeUrl())
                .buildAndExpand(Map.of("client_id", properties.clientId()))
                .toUriString();

        try {
            restClient
                    .method(HttpMethod.DELETE)
                    .uri(grantRevokeUri)
                    .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("access_token", accessToken))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            if (statusCode == HttpStatus.NOT_FOUND.value() || statusCode == HttpStatus.UNAUTHORIZED.value()) {
                return true;
            }
            log.warn("GitHub grant revoke request failed. error={}", exception.getMessage());
            return false;
        } catch (RestClientException exception) {
            log.warn("GitHub grant revoke request failed. error={}", exception.getMessage());
            return false;
        }
    }

    // 400·401·403만 확정된 인증 실패(폐기)로 판정한다. 429(rate limit)·404 등 나머지 4xx와
    // 5xx는 GitHub 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        return statusCode == HttpStatus.BAD_REQUEST.value()
                || statusCode == HttpStatus.UNAUTHORIZED.value()
                || statusCode == HttpStatus.FORBIDDEN.value();
    }

    // bad_refresh_token만 사용자 토큰 폐기다. incorrect_client_credentials 같은 앱 설정 오류는
    // 여기 넣으면 아직 유효한 행을 지운다.
    private static boolean isRevokedRefreshError(GitHubAccessTokenResponse response) {
        return response != null && "bad_refresh_token".equals(response.error());
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

    // 사용자 access token으로 설치 저장소 목록 조회. 설치 토큰은 설치에 열린 저장소 전부를
    // 돌려줘서 사용자가 볼 수 없는 비공개 저장소까지 노출한다.
    public List<GitHubRepositoryResponse> fetchUserInstallationRepositories(
            String userAccessToken,
            Long githubInstallationId
    ) {
        List<GitHubRepositoryResponse> repositories = new ArrayList<>();
        int page = 1;
        while (true) {
            List<GitHubRepositoryResponse> pageRepositories = fetchUserInstallationRepositoryPage(
                    userAccessToken,
                    githubInstallationId,
                    page
            );
            repositories.addAll(pageRepositories);
            if (pageRepositories.size() < REPOSITORIES_PER_PAGE) {
                return repositories;
            }
            page++;
        }
    }

    private List<GitHubRepositoryResponse> fetchUserInstallationRepositoryPage(
            String userAccessToken,
            Long githubInstallationId,
            int page
    ) {
        GitHubRepositoriesResponse response;
        try {
            response = restClient
                    .get()
                    .uri(userInstallationRepositoryPageUri(githubInstallationId, page))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubRepositoriesResponse.class);
        } catch (RestClientResponseException exception) {
            // 저장소 0개 설치는 404를 줄 수 있다는 게 문서로 확정되진 않았지만, 방어적으로
            // 빈 페이지로 처리한다 — 페이지네이션 루프가 정상 종료되고 프론트엔 "저장소 없음"으로 보인다.
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return List.of();
            }
            throw gitHubApiException("GitHub repository list request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("GitHub repository list request failed.", exception);
        }

        if (response == null || response.repositories() == null) {
            return List.of();
        }
        return response.repositories();
    }

    private String userInstallationRepositoryPageUri(Long githubInstallationId, int page) {
        return UriComponentsBuilder.fromUriString(properties.userInstallationRepositoriesUrl())
                .queryParam("per_page", REPOSITORIES_PER_PAGE)
                .queryParam("page", page)
                .buildAndExpand(githubInstallationId)
                .toUriString();
    }

    public List<String> fetchRepositoryBranches(String userAccessToken, String owner, String repo) {
        List<String> branches = new ArrayList<>();
        int page = 1;
        while (true) {
            List<GitHubBranchResponse> pageBranches = fetchRepositoryBranchPage(
                    userAccessToken,
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
            String userAccessToken,
            String owner,
            String repo,
            int page
    ) {
        GitHubBranchResponse[] response;
        try {
            response = restClient
                    .get()
                    .uri(branchPageUri(owner, repo, page))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubBranchResponse[].class);
        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            if (statusCode == HttpStatus.FORBIDDEN.value() || statusCode == HttpStatus.NOT_FOUND.value()) {
                // 403과 404를 구분해 돌려주면 저장소 존재 여부를 누설한다
                throw new NotFoundException("GitHub repository not found.");
            }
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
        return new BadGatewayException(
                message + " GitHub responded with " + exception.getStatusCode() + ".",
                exception
        );
    }

    // installation 접근 판정 3상태 — DENIED만 진짜 접근 없음이고, UNKNOWN은 판단 보류(장애 등)다.
    // 둘을 boolean으로 합치면 장애 상황에서 멀쩡한 멤버십이 지워지는 회귀가 생긴다.
    public enum InstallationAccess {
        ACCESSIBLE,
        DENIED,
        UNKNOWN
    }
}
