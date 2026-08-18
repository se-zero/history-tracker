package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(5),
                detailExecutor()
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
                new GitHubRateLimiter(0, 0, 0),
                Duration.ZERO,
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        service.fetchCommitPage(context, 1, Map.of());
        service.fetchCommitPage(context, 1, Map.of());

        assertThat(userProfileCallCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("merge commit(parents 2개 이상)은 상세 조회 없이 결과에서 제외된다")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_mergeCommit_skipsDetailFetchAndFiltered() {
        Map<String, AtomicInteger> detailCallCounts = new ConcurrentHashMap<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(mergeCommitsPageJson()));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-normal")) {
                        detailCallCounts.computeIfAbsent("sha-normal", k -> new AtomicInteger()).incrementAndGet();
                        return Mono.just(jsonResponse("{}"));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-merge")) {
                        detailCallCounts.computeIfAbsent("sha-merge", k -> new AtomicInteger()).incrementAndGet();
                        return Mono.just(jsonResponse("{}"));
                    }
                    if (path.equals("/users/dev1") || path.equals("/users/dev2")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        assertThat(detailCallCounts.getOrDefault("sha-normal", new AtomicInteger()).get()).isEqualTo(1);
        assertThat(detailCallCounts.getOrDefault("sha-merge", new AtomicInteger()).get()).isEqualTo(0);
        assertThat(page.items()).hasSize(1);
        assertThat(((Map<String, Object>) page.items().get(0)).get("sha")).isEqualTo("sha-normal");
    }

    @Test
    @DisplayName("parents 필드가 없는 커밋은 non-merge로 간주해 상세 조회하고 결과에 포함한다")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_missingParents_treatedAsNonMergeAndFetched() {
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse("""
                                [
                                  {
                                    "sha": "sha-no-parents",
                                    "commit": {
                                      "message": "feat: work",
                                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                                      "committer": {"date": "2024-01-02T00:00:00Z"}
                                    },
                                    "author": {"login": "dev1"}
                                  }
                                ]
                                """));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-no-parents")) {
                        detailCallCount.incrementAndGet();
                        return Mono.just(jsonResponse("{}"));
                    }
                    if (path.equals("/users/dev1")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        assertThat(detailCallCount.get()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
    }

    @Test
    @DisplayName("커밋 상세가 403이면 조용히 삼키지 않고 Retry-After만큼 대기 후 재시도해 files를 포함한 커밋을 반환한다")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_commitDetail403WithRetryAfter_retriesAndReturnsFilesOnSecondCall() {
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        if (detailCallCount.incrementAndGet() == 1) {
                            return Mono.just(rateLimitedResponse(HttpStatus.FORBIDDEN, "7"));
                        }
                        return Mono.just(jsonResponse("""
                                {"files": [{"filename": "src/App.java", "additions": 1, "deletions": 0}]}
                                """));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        assertThat(detailCallCount.get()).isEqualTo(2);
        verify(rateLimiter).awaitRetry(7L);
        Map<String, Object> commit = (Map<String, Object>) page.items().get(0);
        assertThat(commit.get("files")).isNotNull();
    }

    @Test
    @DisplayName("커밋 상세가 429로 계속 실패하면 최대 재시도(3회) 후 GitHubRateLimitedException을 전파한다")
    void fetchCommitPage_commitDetail429Persists_throwsRateLimitedExceptionAfterMaxRetries() {
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        detailCallCount.incrementAndGet();
                        return Mono.just(rateLimitedResponse(HttpStatus.TOO_MANY_REQUESTS, "1"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        assertThatThrownBy(() -> service.fetchCommitPage(context, 1, Map.of()))
                .isInstanceOf(GitHubRawService.GitHubRateLimitedException.class);

        assertThat(detailCallCount.get()).isEqualTo(4);
        verify(rateLimiter, times(3)).awaitRetry(1L);
    }

    @Test
    @DisplayName("커밋 상세가 429이고 Retry-After 헤더가 없으면 60초로 폴백해 대기한다")
    void fetchCommitPage_commitDetail429MissingRetryAfter_fallsBackTo60SecondWait() {
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        if (detailCallCount.incrementAndGet() == 1) {
                            return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                    .body("{}")
                                    .build());
                        }
                        return Mono.just(jsonResponse("""
                                {"files": [{"filename": "src/App.java", "additions": 1, "deletions": 0}]}
                                """));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        service.fetchCommitPage(context, 1, Map.of());

        verify(rateLimiter).awaitRetry(60L);
    }

    @Test
    @DisplayName("Retry-After 없는 403이라도 X-RateLimit-Reset이 있으면 리셋까지 남은 시간만큼 대기한다 (primary limit 소진 대응)")
    void fetchCommitPage_commitDetail403WithResetHeader_waitsUntilReset() {
        long resetEpoch = System.currentTimeMillis() / 1000 + 30;
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        if (detailCallCount.incrementAndGet() == 1) {
                            // primary limit 소진 403: Retry-After 없이 remaining=0 + reset으로만 알려온다
                            return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                    .header("X-RateLimit-Remaining", "0")
                                    .header("X-RateLimit-Reset", String.valueOf(resetEpoch))
                                    .body("{}")
                                    .build());
                        }
                        return Mono.just(jsonResponse("""
                                {"files": [{"filename": "src/App.java", "additions": 1, "deletions": 0}]}
                                """));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );

        service.fetchCommitPage(fetchContext(), 1, Map.of());

        ArgumentCaptor<Long> waited = ArgumentCaptor.forClass(Long.class);
        verify(rateLimiter).awaitRetry(waited.capture());
        assertThat(waited.getValue()).isBetween(28L, 32L);
    }

    @Test
    @DisplayName("rate limit 신호(Retry-After·remaining=0)가 없는 권한성 403은 재시도 없이 즉시 실패한다")
    void fetchCommitPage_permission403WithoutRateLimitSignal_failsImmediatelyWithoutRetry() {
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        detailCallCount.incrementAndGet();
                        // 권한성 403: remaining이 넉넉히 남아 있고 Retry-After도 없다 — 기다려도 안 풀린다
                        return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                .header("X-RateLimit-Remaining", "4999")
                                .header("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 1800))
                                .body("{\"message\": \"Resource not accessible\"}")
                                .build());
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );

        assertThatThrownBy(() -> service.fetchCommitPage(fetchContext(), 1, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(detailCallCount.get()).isEqualTo(1);
        verify(rateLimiter, times(0)).awaitRetry(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("403은 Retry-After 또는 remaining=0일 때만, 429는 항상 rate limit으로 분류한다")
    void isRateLimitResponse_classifiesByStatusAndHeaders() {
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.set("Retry-After", "7");
        HttpHeaders exhausted = new HttpHeaders();
        exhausted.set("X-RateLimit-Remaining", "0");
        HttpHeaders healthy = new HttpHeaders();
        healthy.set("X-RateLimit-Remaining", "4999");

        assertThat(GitHubRawService.isRateLimitResponse(429, new HttpHeaders())).isTrue();
        assertThat(GitHubRawService.isRateLimitResponse(403, retryAfter)).isTrue();
        assertThat(GitHubRawService.isRateLimitResponse(403, exhausted)).isTrue();
        assertThat(GitHubRawService.isRateLimitResponse(403, healthy)).isFalse();
        assertThat(GitHubRawService.isRateLimitResponse(404, retryAfter)).isFalse();
    }

    @Test
    @DisplayName("재시도 대기 시간은 리셋 주기(1시간)를 상한으로 한다")
    void resolveRetryWaitSeconds_capsAtOneHour() {
        HttpHeaders farReset = new HttpHeaders();
        farReset.set("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 100_000));
        assertThat(GitHubRawService.resolveRetryWaitSeconds(farReset)).isEqualTo(3600L);

        HttpHeaders hugeRetryAfter = new HttpHeaders();
        hugeRetryAfter.set("Retry-After", "100000");
        assertThat(GitHubRawService.resolveRetryWaitSeconds(hugeRetryAfter)).isEqualTo(3600L);
    }

    @Test
    @DisplayName("재시도 대기 시간은 Retry-After → X-RateLimit-Reset → 60초 순으로 정한다")
    void resolveRetryWaitSeconds_prefersRetryAfterThenResetThenFallback() {
        long resetIn30 = System.currentTimeMillis() / 1000 + 30;

        HttpHeaders both = new HttpHeaders();
        both.set("Retry-After", "7");
        both.set("X-RateLimit-Reset", String.valueOf(resetIn30));
        assertThat(GitHubRawService.resolveRetryWaitSeconds(both)).isEqualTo(7L);

        HttpHeaders resetOnly = new HttpHeaders();
        resetOnly.set("X-RateLimit-Reset", String.valueOf(resetIn30));
        assertThat(GitHubRawService.resolveRetryWaitSeconds(resetOnly)).isBetween(28L, 32L);

        HttpHeaders pastReset = new HttpHeaders();
        pastReset.set("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 - 100));
        assertThat(GitHubRawService.resolveRetryWaitSeconds(pastReset)).isZero();

        assertThat(GitHubRawService.resolveRetryWaitSeconds(new HttpHeaders())).isEqualTo(60L);
    }

    @Test
    @DisplayName("커밋 상세가 404 등 rate limit이 아닌 오류면 재시도 없이 IllegalStateException을 전파한다")
    void fetchCommitPage_commitDetail404_throwsIllegalStateWithoutRetry() {
        AtomicInteger detailCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        detailCallCount.incrementAndGet();
                        return Mono.just(errorResponse(HttpStatus.NOT_FOUND));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        assertThatThrownBy(() -> service.fetchCommitPage(context, 1, Map.of()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(detailCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("프로필 조회가 429로 계속 실패해도 재시도 소진 후 예외를 삼키고 커밋 수집은 정상 진행한다(프로필만 결손)")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_userProfile429Persists_swallowedAndCommitStillCollected() {
        AtomicInteger profileCallCount = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(commitsPageJson("sha1", "dev")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha1")) {
                        return Mono.just(jsonResponse("""
                                {"files": [{"filename": "src/App.java", "additions": 1, "deletions": 0}]}
                                """));
                    }
                    if (path.equals("/users/dev")) {
                        profileCallCount.incrementAndGet();
                        return Mono.just(rateLimitedResponse(HttpStatus.TOO_MANY_REQUESTS, "2"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });
        GitHubRateLimiter rateLimiter = mock(GitHubRateLimiter.class);
        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                rateLimiter,
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        assertThat(page.items()).hasSize(1);
        Map<String, Object> commit = (Map<String, Object>) page.items().get(0);
        assertThat(commit.get("files")).isNotNull();
        Map<String, Object> author = (Map<String, Object>) commit.get("author");
        assertThat(author).doesNotContainKeys("email", "name");
        assertThat(profileCallCount.get()).isEqualTo(4);
        verify(rateLimiter, times(3)).awaitRetry(2L);
    }

    @Test
    @DisplayName("Retry-After 헤더가 정수 초 문자열이면 그대로 파싱한다")
    void parseRetryAfterSeconds_parsesIntegerSeconds() {
        assertThat(GitHubRawService.parseRetryAfterSeconds("7")).isEqualTo(7L);
    }

    @Test
    @DisplayName("Retry-After 헤더가 없거나 형식이 잘못되면 60초로 폴백한다")
    void parseRetryAfterSeconds_missingOrMalformedHeader_fallsBackTo60() {
        assertThat(GitHubRawService.parseRetryAfterSeconds(null)).isEqualTo(60L);
        assertThat(GitHubRawService.parseRetryAfterSeconds("abc")).isEqualTo(60L);
        assertThat(GitHubRawService.parseRetryAfterSeconds("-5")).isEqualTo(60L);
    }

    @Test
    @DisplayName("커밋 상세 조회가 병렬로 실행돼도 반환 순서는 입력 순서를 유지하고 files가 교차 배선되지 않는다")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_parallelDetailFetch_preservesInputOrderAndFileMapping() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(threeCommitsPageJson()));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-1")) {
                        return Mono.just(jsonResponse(detailFilesJson("sha-1"))).delayElement(Duration.ofMillis(150));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-2")) {
                        return Mono.just(jsonResponse(detailFilesJson("sha-2"))).delayElement(Duration.ofMillis(75));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-3")) {
                        return Mono.just(jsonResponse(detailFilesJson("sha-3")));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) page.items();
        assertThat(items).extracting(item -> item.get("sha")).containsExactly("sha-1", "sha-2", "sha-3");
        for (Map<String, Object> item : items) {
            String sha = (String) item.get("sha");
            List<Map<String, Object>> files = (List<Map<String, Object>>) item.get("files");
            assertThat(files.get(0).get("filename")).isEqualTo(sha + ".txt");
        }
    }

    @Test
    @DisplayName("커밋 상세 조회 2건이 동시에 in-flight 상태가 된다(순차 실행이면 서로를 기다리다 타임아웃한다)")
    void fetchCommitPage_detailFetch_runsConcurrently() {
        CountDownLatch bothInFlight = new CountDownLatch(2);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(twoCommitsPageJson()));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-a") || path.equals("/repos/owner/repo/commits/sha-b")) {
                        bothInFlight.countDown();
                        boolean bothArrived;
                        try {
                            bothArrived = bothInFlight.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            bothArrived = false;
                        }
                        if (!bothArrived) {
                            return Mono.just(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR));
                        }
                        return Mono.just(jsonResponse("{}"));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        assertThat(page.items()).hasSize(2);
    }

    @Test
    @DisplayName("커밋 2개 중 1개의 상세 조회가 실패하면 원 예외를 언랩해 페이지 전체를 실패시킨다")
    void fetchCommitPage_oneOfTwoDetailFetchesFails_propagatesUnwrappedCause() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(twoCommitsPageJson()));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-a")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-b")) {
                        return Mono.just(errorResponse(HttpStatus.NOT_FOUND));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        assertThatThrownBy(() -> service.fetchCommitPage(context, 1, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("merge 커밋은 병렬화 이후에도 상세 조회 대상에서 제외되고, 나머지 커밋의 반환 순서는 입력 순서를 유지한다")
    @SuppressWarnings("unchecked")
    void fetchCommitPage_mergeFilterAndParallelFetch_skipsMergeAndPreservesOrder() {
        Map<String, AtomicInteger> detailCallCounts = new ConcurrentHashMap<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    if (path.equals("/repos/owner/repo/commits")) {
                        return Mono.just(jsonResponse(mergeAndTwoNormalCommitsPageJson()));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-x")) {
                        detailCallCounts.computeIfAbsent("sha-x", k -> new AtomicInteger()).incrementAndGet();
                        return Mono.just(jsonResponse(detailFilesJson("sha-x")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-y")) {
                        detailCallCounts.computeIfAbsent("sha-y", k -> new AtomicInteger()).incrementAndGet();
                        return Mono.just(jsonResponse(detailFilesJson("sha-y")));
                    }
                    if (path.equals("/repos/owner/repo/commits/sha-merge")) {
                        detailCallCounts.computeIfAbsent("sha-merge", k -> new AtomicInteger()).incrementAndGet();
                        return Mono.just(jsonResponse("{}"));
                    }
                    if (path.equals("/users/dev")) {
                        return Mono.just(jsonResponse("{}"));
                    }
                    throw new IllegalArgumentException("Unexpected GitHub API path: " + path);
                });

        GitHubRawService service = new GitHubRawService(
                webClientBuilder,
                "https://api.github.example",
                new GitHubRateLimiter(0, 0, 0),
                Duration.ofMinutes(30),
                detailExecutor()
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();

        GitHubRawService.GitHubPage page = service.fetchCommitPage(context, 1, Map.of());

        assertThat(detailCallCounts.getOrDefault("sha-merge", new AtomicInteger()).get()).isEqualTo(0);
        assertThat(detailCallCounts.get("sha-x").get()).isEqualTo(1);
        assertThat(detailCallCounts.get("sha-y").get()).isEqualTo(1);

        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) page.items();
        assertThat(items).extracting(item -> item.get("sha")).containsExactly("sha-x", "sha-y");
    }

    private String mergeCommitsPageJson() {
        return """
                [
                  {
                    "sha": "sha-normal",
                    "commit": {
                      "message": "feat: normal work",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev1"},
                    "parents": [{"sha": "p1"}]
                  },
                  {
                    "sha": "sha-merge",
                    "commit": {
                      "message": "Merge branch 'main'",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev2"},
                    "parents": [{"sha": "p1"}, {"sha": "p2"}]
                  }
                ]
                """;
    }

    private GitHubRawService.GitHubFetchContext fetchContext() {
        return new GitHubRawService.GitHubFetchContext(
                "Bearer token", "owner", "repo", null, GitHubCheckpoint.empty());
    }

    // 커밋 상세 조회 병렬화(동시성 3) 검증용 소형 executor — githubCommitDetailExecutor 빈을 흉내낸다.
    private static AsyncTaskExecutor detailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("test-commit-detail-");
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.initialize();
        return executor;
    }

    private String threeCommitsPageJson() {
        return """
                [
                  {
                    "sha": "sha-1",
                    "commit": {
                      "message": "feat: work1",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  },
                  {
                    "sha": "sha-2",
                    "commit": {
                      "message": "feat: work2",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  },
                  {
                    "sha": "sha-3",
                    "commit": {
                      "message": "feat: work3",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  }
                ]
                """;
    }

    private String twoCommitsPageJson() {
        return """
                [
                  {
                    "sha": "sha-a",
                    "commit": {
                      "message": "feat: work-a",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  },
                  {
                    "sha": "sha-b",
                    "commit": {
                      "message": "feat: work-b",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  }
                ]
                """;
    }

    private String mergeAndTwoNormalCommitsPageJson() {
        return """
                [
                  {
                    "sha": "sha-x",
                    "commit": {
                      "message": "feat: x",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  },
                  {
                    "sha": "sha-merge",
                    "commit": {
                      "message": "Merge branch 'main'",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "p1"}, {"sha": "p2"}]
                  },
                  {
                    "sha": "sha-y",
                    "commit": {
                      "message": "feat: y",
                      "author": {"name": "Dev", "date": "2024-01-01T00:00:00Z"},
                      "committer": {"date": "2024-01-02T00:00:00Z"}
                    },
                    "author": {"login": "dev"},
                    "parents": [{"sha": "parent"}]
                  }
                ]
                """;
    }

    private String detailFilesJson(String sha) {
        return """
                {"files": [{"filename": "%s.txt", "additions": 1, "deletions": 0}]}
                """.formatted(sha);
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

    private ClientResponse rateLimitedResponse(HttpStatus status, String retryAfterSeconds) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("Retry-After", retryAfterSeconds)
                .body("""
                        {"message": "rate limited"}
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
