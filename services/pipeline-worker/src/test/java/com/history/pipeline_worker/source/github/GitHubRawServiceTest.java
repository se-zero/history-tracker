package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.checkpoint.ProjectCheckpointData;
import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GitHubRawServiceTest {

    @Test
    @DisplayName("sample은 1페이지 merged PR만 수집하고 PR commit sha를 raw commit prNumber로 주입")
    @SuppressWarnings("unchecked")
    void fetchSample_collectsOnlyMergedPrsAndInjectsCommitPrNumber() {
        AtomicBoolean searchApiCalled = new AtomicBoolean(false);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if (request.url().getPath().startsWith("/search")) {
                        searchApiCalled.set(true);
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );

        Map<String, Object> raw = service.fetchSample(new RawFetchRequest("Bearer token", "owner/repo", Map.of()));

        List<Map<String, Object>> pullRequests = (List<Map<String, Object>>) raw.get("pullRequests");
        assertThat(pullRequests).hasSize(1);
        assertThat(pullRequests.get(0)).containsEntry("number", 10);

        List<Map<String, Object>> commits = (List<Map<String, Object>>) raw.get("commits");
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0)).containsEntry("sha", "sha-pr")
                .containsEntry("prNumber", "10");
        assertThat(searchApiCalled).isFalse();
    }

    @Test
    @DisplayName("commit.author(GitHub 계정)에 프로필 name/email이 보강된다")
    @SuppressWarnings("unchecked")
    void fetchSample_commitAuthor_enrichedWithProfileNameAndEmail() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(jsonResponse(responseFor(request))));

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );

        Map<String, Object> raw = service.fetchSample(new RawFetchRequest("Bearer token", "owner/repo", Map.of()));

        List<Map<String, Object>> commits = (List<Map<String, Object>>) raw.get("commits");
        Map<String, Object> author = (Map<String, Object>) commits.get(0).get("author");
        assertThat(author).containsEntry("login", "dev")
                .containsEntry("name", "Dev")
                .containsEntry("email", "dev@example.com");
    }

    @Test
    @DisplayName("branch 옵션 지정 시 commits 요청 URL에 sha 파라미터 포함")
    void prepareFetchContext_withBranchOption_addsShaParam() {
        AtomicReference<String> capturedCommitsQuery = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if (request.url().getPath().equals("/repos/owner/repo/commits")) {
                        capturedCommitsQuery.set(request.url().getQuery());
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );

        service.fetchSample(new RawFetchRequest("Bearer token", "owner/repo", Map.of("branch", "develop")));

        assertThat(capturedCommitsQuery.get()).contains("sha=develop");
    }

    @Test
    @DisplayName("branch 옵션 미지정 시 commits 요청 URL에 sha 파라미터 미포함")
    void prepareFetchContext_withoutBranchOption_noShaParam() {
        AtomicReference<String> capturedCommitsQuery = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    if (request.url().getPath().equals("/repos/owner/repo/commits")) {
                        capturedCommitsQuery.set(request.url().getQuery());
                    }
                    return Mono.just(jsonResponse(responseFor(request)));
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );

        service.fetchSample(new RawFetchRequest("Bearer token", "owner/repo", Map.of()));

        assertThat(capturedCommitsQuery.get()).doesNotContain("sha=");
    }

    @Test
    @DisplayName("프로필 조회 HTTP 에러 응답은 캐시하지 않고 다음 호출에서 재조회한다")
    void fetchCommitPage_userProfileHttpError_notCachedAndRetried() {
        AtomicInteger userProfileCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/users/dev")) {
                        userProfileCallCount.incrementAndGet();
                        return Mono.just(errorResponse(HttpStatus.FORBIDDEN));
                    }
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        service.fetchCommitPage(context, 1, Map.of());
        service.fetchCommitPage(context, 1, Map.of());

        assertThat(userProfileCallCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("프로필 조회 중 예외가 발생해도 enrichCommits는 예외를 전파하지 않고 보강 없이 진행하며, 실패한 조회는 캐시하지 않는다")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_userProfileFetchThrows_doesNotPropagateAndNotCached() {
        AtomicInteger userProfileCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/users/dev")) {
                        if (userProfileCallCount.incrementAndGet() == 1) {
                            return Mono.error(new RuntimeException("connection reset"));
                        }
                        return Mono.just(jsonResponse("""
                                {"email": "dev@example.com", "name": "Dev"}
                                """));
                    }
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        AtomicReference<GitHubRawService.GitHubPage> firstPageRef = new AtomicReference<>();
        assertThatCode(() -> firstPageRef.set(service.fetchCommitPage(context, 1, Map.of())))
                .doesNotThrowAnyException();

        Map<String, Object> firstAuthor = (Map<String, Object>) firstPageRef.get().items().get(0);
        firstAuthor = (Map<String, Object>) firstAuthor.get("author");
        assertThat(firstAuthor).doesNotContainKeys("email", "name");

        GitHubRawService.GitHubPage secondPage = service.fetchCommitPage(context, 1, Map.of());
        Map<String, Object> secondAuthor = (Map<String, Object>) ((Map<String, Object>) secondPage.items().get(0)).get("author");
        assertThat(secondAuthor).containsEntry("email", "dev@example.com").containsEntry("name", "Dev");
        assertThat(userProfileCallCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("성공한 프로필 조회는 캐시되어 같은 login 재조회 시 exchange가 1회만 호출된다")
    void fetchCommitPage_userProfileSuccess_cachedAcrossCalls() {
        AtomicInteger userProfileCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/users/dev")) {
                        userProfileCallCount.incrementAndGet();
                        return Mono.just(jsonResponse("""
                                {"email": "dev@example.com", "name": "Dev"}
                                """));
                    }
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(30)
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        service.fetchCommitPage(context, 1, Map.of());
        service.fetchCommitPage(context, 1, Map.of());

        assertThat(userProfileCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL 내 재호출은 프로필을 재조회하지 않고 캐시를 재사용한다")
    void fetchCommitPage_userProfileWithinTtl_cachedAcrossCalls() {
        AtomicInteger userProfileCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/users/dev")) {
                        userProfileCallCount.incrementAndGet();
                        return Mono.just(jsonResponse("""
                                {"email": "dev@example.com", "name": "Dev"}
                                """));
                    }
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ofMinutes(5)
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        service.fetchCommitPage(context, 1, Map.of());
        service.fetchCommitPage(context, 1, Map.of());

        assertThat(userProfileCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL=0이면 캐시가 비활성화되어 매번 프로필을 재조회한다")
    void fetchCommitPage_userProfileTtlZero_refetchedEachCall() {
        AtomicInteger userProfileCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/users/dev")) {
                        userProfileCallCount.incrementAndGet();
                        return Mono.just(jsonResponse("""
                                {"email": "dev@example.com", "name": "Dev"}
                                """));
                    }
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0),
                Duration.ZERO
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        service.fetchCommitPage(context, 1, Map.of());
        service.fetchCommitPage(context, 1, Map.of());

        assertThat(userProfileCallCount.get()).isEqualTo(2);
    }

    private GitHubRawService.GitHubFetchContext fetchContext() {
        return new GitHubRawService.GitHubFetchContext(
                "Bearer token", "owner", "repo", null, new ProjectCheckpointData.GitHubCheckpoint());
    }

    private String commitsPageJson(String sha, String login) {
        return """
                [
                  {
                    "sha": "%s",
                    "commit": {
                      "message": "feat: work",
                      "author": {"name": "Dev", "email": "dev@example.com", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "%s"},
                    "parents": [{"sha": "parent"}]
                  }
                ]
                """.formatted(sha, login);
    }

    private ClientResponse errorResponse(HttpStatus status) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("""
                        {"message": "API rate limit exceeded"}
                        """)
                .build();
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build();
    }

    private String responseFor(ClientRequest request) {
        String path = request.url().getPath();
        String query = request.url().getQuery();
        if ("/repos/owner/repo/pulls".equals(path)) {
            if (query != null && query.contains("page=1")) {
                return """
                        [
                          {
                            "number": 10,
                            "title": "merged",
                            "state": "closed",
                            "created_at": "2024-01-01T00:00:00Z",
                            "updated_at": "2024-01-03T00:00:00Z",
                            "merged_at": "2024-01-02T00:00:00Z",
                            "merge_commit_sha": "merge-sha",
                            "user": {"login": "dev"},
                            "base": {"ref": "main"},
                            "html_url": "https://github.com/owner/repo/pull/10"
                          },
                          {
                            "number": 11,
                            "title": "closed only",
                            "state": "closed",
                            "created_at": "2024-01-01T00:00:00Z",
                            "updated_at": "2024-01-03T00:00:00Z",
                            "merged_at": null,
                            "user": {"login": "dev"},
                            "base": {"ref": "main"},
                            "html_url": "https://github.com/owner/repo/pull/11"
                          }
                        ]
                        """;
            }
            return "[]";
        }
        if ("/repos/owner/repo/pulls/10/commits".equals(path)) {
            return """
                    [
                      {"sha": "sha-pr"}
                    ]
                    """;
        }
        if ("/repos/owner/repo/commits".equals(path)) {
            if (query != null && query.contains("page=1")) {
                return """
                        [
                          {
                            "sha": "sha-pr",
                            "commit": {
                              "message": "feat: merged work",
                              "author": {
                                "name": "Dev",
                                "email": "dev@example.com",
                                "date": "2024-01-01T00:00:00Z"
                              },
                              "committer": {
                                "date": "2024-01-02T00:00:00Z"
                              }
                            },
                            "author": {"login": "dev"},
                            "parents": [{"sha": "parent"}]
                          }
                        ]
                        """;
            }
            return "[]";
        }
        if ("/repos/owner/repo/commits/sha-pr".equals(path)) {
            return """
                    {
                      "files": [
                        {"filename": "src/App.java", "patch": "@@", "additions": 1, "deletions": 0}
                      ]
                    }
                    """;
        }
        if ("/repos/owner/repo/issues".equals(path)) {
            return "[]";
        }
        if ("/users/dev".equals(path)) {
            return """
                    {
                      "email": "dev@example.com",
                      "name": "Dev"
                    }
                    """;
        }
        throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
    }
}
