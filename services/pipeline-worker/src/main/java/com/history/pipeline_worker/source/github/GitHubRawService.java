package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
@Service
public class GitHubRawService {

    private static final int PER_PAGE = 100; // GitHub API 최대값

    // 403/429 재시도 최대 횟수 (Slack/Discord와 동일 기준)
    private static final int MAX_RETRY_ON_RATE_LIMIT = 3;

    // resolvedProfiles는 가변 맵이다 — 이 실행(fetchContext)에서 보강한 login별 프로필을 페이지를
    // 넘나들며 누적하는 자리라서다. record 성분이라고 불변을 가정하면 안 된다. 실행이 끝나면
    // context와 함께 버려지므로 개인정보가 프로세스 수명만큼 남아있던 문제(전역 싱글턴 캐시)를 없앤다.
    // GitHubCollector가 한 실행의 PR·commit·issue를 한 스레드에서 순차 처리하고 프로필 보강도
    // caller 스레드에서만 일어나므로(§ enrichCommits) 동시 접근이 없어 HashMap으로 충분하다.
    public record GitHubFetchContext(String auth, String owner, String repo, String branch,
                                     GitHubCheckpoint checkpoint, Map<String, Map<String, String>> resolvedProfiles) {}
    public record GitHubPage(List<Object> items, boolean finished) {}

    static class GitHubRateLimitedException extends RuntimeException {
        final long retryAfterSeconds;

        GitHubRateLimitedException(long retryAfterSeconds) {
            super("GitHub rate limited, Retry-After=" + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private final WebClient webClient;
    private final GitHubRateLimiter rateLimiter;
    private final AsyncTaskExecutor commitDetailExecutor;

    public GitHubRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.github.base-url}") String baseUrl,
            GitHubRateLimiter rateLimiter,
            @Qualifier("githubCommitDetailExecutor") AsyncTaskExecutor commitDetailExecutor
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.rateLimiter = rateLimiter;
        this.commitDetailExecutor = commitDetailExecutor;
    }

    public GitHubFetchContext prepareFetchContext(
            RawFetchRequest request,
            GitHubCheckpoint checkpoint
    ) {
        String[] parts = request.projectKey().split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("projectKey must be in 'owner/repo' format");
        }
        String owner = parts[0];
        String repo = parts[1];
        String auth = request.credentials();

        String branch = request.options() != null ? request.options().getOrDefault("branch", null) : null;
        return new GitHubFetchContext(auth, owner, repo, branch, checkpoint, new HashMap<>());
    }

    public Map<String, Object> fetchSample(RawFetchRequest request) {
        GitHubFetchContext context = prepareFetchContext(request, GitHubCheckpoint.empty());
        GitHubPage pullRequestPage = fetchMergedPullRequestPage(context, 1);
        Map<String, String> commitPrNumbers = fetchCommitPrNumbers(context, pullRequestPage.items());
        GitHubPage commitPage = fetchCommitPage(context, 1, commitPrNumbers);
        GitHubPage issuePage = fetchIssuePage(context, 1);

        return Map.of(
                "commits", commitPage.items(),
                "pullRequests", pullRequestPage.items(),
                "issues", issuePage.items()
        );
    }

    public GitHubPage fetchMergedPullRequestPage(GitHubFetchContext context, int page) {
        // base 브랜치로 필터링 — 지정 브랜치를 타겟으로 한 PR만 수집(미지정 시 전체 브랜치)
        String baseParam = context.branch() != null ? "&base=" + context.branch() : "";
        GitHubPage closedPullRequests = fetchPageAfterCheckpoint(
                context.auth(),
                "/repos/{owner}/{repo}/pulls?state=closed&sort=updated&direction=desc&per_page=" + PER_PAGE + baseParam,
                context.owner(),
                context.repo(),
                context.checkpoint().pullRequestsScannedAt(),
                "updated_at",
                page
        );

        List<Object> mergedPullRequests = filterMergedPullRequests(
                closedPullRequests.items(),
                context.checkpoint().pullRequestsScannedAt()
        );
        return new GitHubPage(enrichUserObjects(context, mergedPullRequests), closedPullRequests.finished());
    }

    public Map<String, String> fetchCommitPrNumbers(GitHubFetchContext context, List<Object> pullRequests) {
        return fetchCommitPrNumbers(context.auth(), pullRequests, context.owner(), context.repo());
    }

    public GitHubPage fetchCommitPage(GitHubFetchContext context, int page, Map<String, String> commitPrNumbers) {
        String branchParam = context.branch() != null ? "&sha=" + context.branch() : "";
        GitHubPage rawCommits = fetchPageAfterCheckpoint(
                context.auth(),
                "/repos/{owner}/{repo}/commits?per_page=" + PER_PAGE + branchParam,
                context.owner(),
                context.repo(),
                context.checkpoint().commitsScannedAt(),
                "commit.committer.date",
                page
        );
        return new GitHubPage(
                enrichCommits(context, rawCommits.items(), commitPrNumbers),
                rawCommits.finished()
        );
    }

    public GitHubPage fetchIssuePage(GitHubFetchContext context, int page) {
        GitHubPage issues = fetchPageAfterCheckpoint(
                context.auth(),
                "/repos/{owner}/{repo}/issues?state=all&sort=updated&direction=desc&per_page=" + PER_PAGE,
                context.owner(),
                context.repo(),
                context.checkpoint().issuesScannedAt(),
                "updated_at",
                page
        );
        return new GitHubPage(enrichUserObjects(context, issues.items()), issues.finished());
    }

    @SuppressWarnings("unchecked")
    private GitHubPage fetchPageAfterCheckpoint(String auth, String basePath, String owner, String repo,
                                                Instant lastScannedAt, String dateField, int page) {
        List<Object> pageItems = fetchPage(auth, basePath + "&page=" + page, owner, repo);
        if (pageItems.isEmpty()) return new GitHubPage(List.of(), true);

        List<Object> items = new ArrayList<>();
        for (Object item : pageItems) {
            if (lastScannedAt != null) {
                String dateStr = extractNestedStr((Map<String, Object>) item, dateField);
                if (dateStr != null) {
                    try {
                        if (!Instant.parse(dateStr).isAfter(lastScannedAt)) {
                            log.debug("체크포인트 도달 (page={}, date={}), 수집 종료", page, dateStr);
                            return new GitHubPage(items, true);
                        }
                    } catch (Exception ignored) {}
                }
            }
            items.add(item);
        }

        return new GitHubPage(items, pageItems.size() < PER_PAGE);
    }

    /**
     * commit.author(GitHub 계정)에 프로필 name/email 보강. 실행 단위 재사용으로 이 실행에 등장한
     * 기여자 수만큼만 호출한다.
     * 상세 조회(GET /commits/{sha})는 페이지당 최대 100건이라 caller 스레드에서 순차 호출하면 지연이
     * 커져 전용 풀(githubCommitDetailExecutor, 동시 3)에 병렬 제출한다(2-phase).
     */
    @SuppressWarnings("unchecked")
    private List<Object> enrichCommits(GitHubFetchContext context, List<Object> commits,
                                       Map<String, String> commitPrNumbers) {
        // phase 1: merge 필터(기존 유지) 통과 커밋만 입력 순서대로 상세 조회를 제출한다.
        // Future 리스트가 입력 순서와 같으므로 phase 2에서 순서대로 join하면 반환 순서도
        // 기존 순차 처리와 동일하게 유지된다(발행 순서 결정성 유지).
        List<Map<String, Object>> filtered = new ArrayList<>();
        List<Future<Map<String, Object>>> detailFutures = new ArrayList<>();
        for (Object raw : commits) {
            Map<String, Object> commit = new HashMap<>((Map<String, Object>) raw);

            // Normalizer가 어차피 버리는 merge commit의 상세 조회 콜(~85콜/초기수집)을 원천 제거.
            // 판정식은 GitHubNormalizer와 동일(parents 결손 = non-merge 취급, 널 관용 유지) —
            // Normalizer 필터는 이중 방어로 존치.
            List<Object> parents = (List<Object>) commit.get("parents");
            if (parents != null && parents.size() > 1) continue;

            String sha = (String) commit.get("sha");
            filtered.add(commit);
            detailFutures.add(commitDetailExecutor.submit(
                    () -> fetchCommitDetail(context.auth(), context.owner(), context.repo(), sha)));
        }

        // phase 2: 입력 순서대로 join → 기존 병합 로직 적용. 프로필 보강은 기존대로 caller 스레드에서 순차 호출한다.
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            Map<String, Object> commit = filtered.get(i);
            String sha = (String) commit.get("sha");
            Map<String, Object> detail = joinCommitDetail(detailFutures, i);
            if (detail != null) {
                commit.put("files", detail.get("files"));
            }
            String prNumber = commitPrNumbers.get(sha);
            if (prNumber != null) {
                commit.put("prNumber", prNumber);
            }
            Map<String, Object> ghAuthor = (Map<String, Object>) commit.get("author");
            if (ghAuthor != null) {
                String login = (String) ghAuthor.get("login");
                if (login != null) {
                    Map<String, String> profile = fetchUserProfile(context, login);
                    Map<String, Object> enrichedAuthor = new HashMap<>(ghAuthor);
                    if (profile.containsKey("email")) enrichedAuthor.put("email", profile.get("email"));
                    if (profile.containsKey("name"))  enrichedAuthor.put("name",  profile.get("name"));
                    commit.put("author", enrichedAuthor);
                }
            }
            result.add(commit);
        }
        return result;
    }

    // future.get()의 ExecutionException은 cause를 언랩해 RuntimeException이면 그대로, checked면 감싸
    // 전파한다(1건 실패 = 페이지 전체 실패 = 기존 순차 처리와 동일 동작, checkpoint 미전진).
    // 실패가 확정되면 아직 join하지 않은 나머지 future를 전부 취소해 rate limit 예산 낭비를 막는다.
    private Map<String, Object> joinCommitDetail(List<Future<Map<String, Object>>> detailFutures, int index) {
        try {
            return detailFutures.get(index).get();
        } catch (ExecutionException e) {
            cancelRemaining(detailFutures, index);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            cancelRemaining(detailFutures, index);
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void cancelRemaining(List<Future<Map<String, Object>>> detailFutures, int fromIndex) {
        for (int i = fromIndex; i < detailFutures.size(); i++) {
            detailFutures.get(i).cancel(true);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fetchCommitPrNumbers(String auth, List<Object> pullRequests, String owner, String repo) {
        Map<String, String> result = new HashMap<>();
        for (Object raw : pullRequests) {
            Map<String, Object> pr = (Map<String, Object>) raw;
            Object numberValue = pr.get("number");
            if (numberValue == null) continue;

            String prNumber = String.valueOf(numberValue);
            Object mergeCommitSha = pr.get("merge_commit_sha");
            if (mergeCommitSha != null) {
                result.putIfAbsent(String.valueOf(mergeCommitSha), prNumber);
            }

            List<Object> prCommits = fetchPullRequestCommits(auth, owner, repo, prNumber);
            for (Object commitRaw : prCommits) {
                Map<String, Object> commit = (Map<String, Object>) commitRaw;
                String sha = (String) commit.get("sha");
                if (sha != null) {
                    result.putIfAbsent(sha, prNumber);
                }
            }
        }
        return result;
    }

    /** PR·Issue의 user 객체에 email·name을 보강 (GET /users/{login}) */
    @SuppressWarnings("unchecked")
    private List<Object> enrichUserObjects(GitHubFetchContext context, List<Object> items) {
        List<Object> result = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = new HashMap<>((Map<String, Object>) raw);
            Map<String, Object> user = (Map<String, Object>) item.get("user");
            if (user != null) {
                String login = (String) user.get("login");
                if (login != null) {
                    Map<String, String> profile = fetchUserProfile(context, login);
                    Map<String, Object> enrichedUser = new HashMap<>(user);
                    if (profile.containsKey("email")) enrichedUser.put("email", profile.get("email"));
                    if (profile.containsKey("name"))  enrichedUser.put("name",  profile.get("name"));
                    item.put("user", enrichedUser);
                }
            }
            result.add(item);
        }
        return result;
    }

    /** GET /users/{login} → {email, name}. 이 실행에서 이미 조회한 login은 재사용한다. */
    @SuppressWarnings("unchecked")
    private Map<String, String> fetchUserProfile(GitHubFetchContext context, String login) {
        Map<String, String> resolved = context.resolvedProfiles().get(login);
        if (resolved != null) {
            return resolved;
        }

        // computeIfAbsent는 매핑 함수의 반환값을 무조건 기록하므로 실패(에러 상태·예외) 케이스를
        // 걸러낼 수 없다. get으로 조회 후 미스일 때만 호출하고 성공한 결과만 기록하는 패턴으로 바꿔,
        // 일시 장애가 그 계정의 신원 보강을 이 실행 내내 영구히 결손시키지 않도록 한다.
        AtomicReference<HttpHeaders> headersRef = new AtomicReference<>();
        try {
            // 프로필은 부가 데이터라 재시도 대상을 429(명백한 rate limit)로만 좁힌다 — 403 등 나머지
            // non-2xx는 즉시 실패시켜 아래 catch가 흡수하고(미기록으로 다음 호출에서 재조회), 계정당
            // 최대 3×Retry-After초를 태우지 않는다.
            Map<String, Object> result = executeWithRateLimitRetry(() -> webClient.get()
                    .uri("/users/{login}", login)
                    .header("Authorization", context.auth())
                    .exchangeToMono(resp -> {
                        headersRef.set(resp.headers().asHttpHeaders());
                        if (resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(Map.class);
                        }
                        if (resp.statusCode().value() == 429) {
                            return Mono.error(new GitHubRateLimitedException(
                                    resolveRetryWaitSeconds(resp.headers().asHttpHeaders())));
                        }
                        return Mono.error(new IllegalStateException(
                                "GitHub API error: status=" + resp.statusCode().value() + ", path=/users/" + login));
                    })
                    .block());

            Map<String, String> profile = new HashMap<>();
            if (result != null) {
                String email = (String) result.get("email");
                String name  = (String) result.get("name");
                if (email != null) profile.put("email", email);
                if (name  != null) profile.put("name",  name);
            }
            context.resolvedProfiles().put(login, profile);
            return profile;
        } catch (Exception e) {
            // 프로필은 부가 데이터라 조회 실패로 커밋/이슈 수집 자체를 막지 않는다.
            log.warn("GitHub user profile 조회 실패 (login={}): {}", login, e.getMessage());
            return Map.of();
        } finally {
            // 성공·실패 공통 1회 페이싱 — 실패 응답(404 등)도 rate limit 예산을 소모하고,
            // 실패는 캐시하지 않아 같은 계정으로 반복 호출될 수 있으므로 페이싱을 유지한다.
            rateLimiter.acquire(headersRef.get());
        }
    }

    // 재시도 소진 시 예외가 그대로 전파돼 이 커밋의 collect가 실패한다 — checkpoint가 전진하지 않아
    // 다음 수집에서 재시도된다("발행 예외를 삼키지 않는다"와 같은 원리, 조용한 데이터 결손보다 낫다).
    // commitDetailExecutor의 worker 스레드에서 호출된다 — rateLimiter.acquire(무상태)·403/429 재시도는 스레드별로 그대로 동작한다.
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCommitDetail(String auth, String owner, String repo, String sha) {
        return executeWithRateLimitRetry(() -> {
            AtomicReference<HttpHeaders> headersRef = new AtomicReference<>();
            Map<String, Object> result = webClient.get()
                    .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                    .header("Authorization", auth)
                    .exchangeToMono(resp -> {
                        HttpHeaders respHeaders = resp.headers().asHttpHeaders();
                        headersRef.set(respHeaders);
                        if (resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(Map.class);
                        }
                        if (isRateLimitResponse(resp.statusCode().value(), respHeaders)) {
                            return Mono.error(new GitHubRateLimitedException(resolveRetryWaitSeconds(respHeaders)));
                        }
                        // 404만 예외 취급하지 않는다 — force-push·history rewrite로 사라진 커밋은
                        // 재시도해도 영원히 404다. 여기서 던지면 페이지 전체가 실패하고 checkpoint가
                        // 전진하지 않아 매 수집이 같은 지점에서 막힌다(자가 복구 불가). files 없이
                        // 넘기고 수집을 계속한다 — 권한(403)·서버 오류는 아래에서 그대로 실패시킨다.
                        if (resp.statusCode().value() == 404) {
                            log.warn("GitHub commit 상세 없음, files 없이 진행 (sha={})", sha);
                            return Mono.empty();
                        }
                        return Mono.error(new IllegalStateException(
                                "GitHub API error: status=" + resp.statusCode().value()
                                        + ", path=/repos/" + owner + "/" + repo + "/commits/" + sha));
                    })
                    .block();
            rateLimiter.acquire(headersRef.get());
            return result;
        });
    }

    private List<Object> fetchPullRequestCommits(String auth, String owner, String repo, String prNumber) {
        List<Object> allItems = new ArrayList<>();
        int page = 1;

        while (true) {
            List<Object> pageItems = fetchPage(
                    auth,
                    "/repos/{owner}/{repo}/pulls/" + prNumber + "/commits?per_page=" + PER_PAGE + "&page=" + page,
                    owner,
                    repo
            );
            if (pageItems.isEmpty()) break;

            allItems.addAll(pageItems);
            if (pageItems.size() < PER_PAGE) break;
            page++;
        }

        return allItems;
    }

    @SuppressWarnings("unchecked")
    private List<Object> filterMergedPullRequests(List<Object> closedPullRequests, Instant lastMergedAt) {
        List<Object> mergedPullRequests = new ArrayList<>();
        for (Object raw : closedPullRequests) {
            Map<String, Object> pr = (Map<String, Object>) raw;
            String mergedAt = (String) pr.get("merged_at");
            if (mergedAt == null) continue;
            if (lastMergedAt != null && !Instant.parse(mergedAt).isAfter(lastMergedAt)) continue;
            mergedPullRequests.add(pr);
        }

        return mergedPullRequests;
    }

    // 재시도 소진 시 예외가 그대로 전파돼 collect가 실패한다 — checkpoint가 전진하지 않아 다음 수집에서
    // 재발행된다("발행 예외를 삼키지 않는다"와 같은 원리).
    @SuppressWarnings("unchecked")
    private List<Object> fetchPage(String auth, String path, String owner, String repo) {
        return executeWithRateLimitRetry(() -> {
            AtomicReference<HttpHeaders> headersRef = new AtomicReference<>();
            List<Object> result = webClient.get()
                    .uri(path, owner, repo)
                    .header("Authorization", auth)
                    .exchangeToMono(resp -> {
                        HttpHeaders respHeaders = resp.headers().asHttpHeaders();
                        headersRef.set(respHeaders);
                        if (resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(List.class);
                        }
                        if (isRateLimitResponse(resp.statusCode().value(), respHeaders)) {
                            return Mono.error(new GitHubRateLimitedException(resolveRetryWaitSeconds(respHeaders)));
                        }
                        return Mono.error(new IllegalStateException(
                                "GitHub API error: status=" + resp.statusCode().value() + ", path=" + path));
                    })
                    .block();
            rateLimiter.acquire(headersRef.get());
            return result != null ? result : List.of();
        });
    }

    /** "commit.author.date" 처럼 점(.) 구분 중첩 경로로 문자열 값 추출 */
    @SuppressWarnings("unchecked")
    private String extractNestedStr(Map<String, Object> map, String dotPath) {
        String[] parts = dotPath.split("\\.", 2);
        Object val = map.get(parts[0]);
        if (parts.length == 1) return val instanceof String ? (String) val : null;
        return val instanceof Map ? extractNestedStr((Map<String, Object>) val, parts[1]) : null;
    }

    // 403/429면 Retry-After 헤더만큼 대기 후 재시도. 소진 시 원 예외를 그대로 재던진다.
    private <T> T executeWithRateLimitRetry(Supplier<T> request) {
        int attempts = 0;
        while (true) {
            try {
                return request.get();
            } catch (GitHubRateLimitedException e) {
                attempts++;
                if (attempts > MAX_RETRY_ON_RATE_LIMIT) {
                    throw e;
                }
                log.warn("GitHub rate limit(403/429) — {}초 대기 후 재시도 ({}/{})",
                        e.retryAfterSeconds, attempts, MAX_RETRY_ON_RATE_LIMIT);
                rateLimiter.awaitRetry(e.retryAfterSeconds);
            }
        }
    }

    // 403을 재시도 대상으로 삼는 판별자: rate limit 신호(Retry-After 존재 또는 remaining 소진)가
    // 있을 때만. 권한성 403(레포 접근 상실 등)은 기다려도 안 풀리므로 즉시 실패시켜 빨리 드러낸다.
    // 429는 항상 rate limit이다.
    static boolean isRateLimitResponse(int status, HttpHeaders headers) {
        if (status == 429) return true;
        if (status != 403) return false;
        return headers.getFirst("Retry-After") != null
                || "0".equals(headers.getFirst("X-RateLimit-Remaining"));
    }

    // 재시도 대기 상한. GitHub primary limit의 리셋 주기가 1시간이라 그 이상의 대기는 무의미하다.
    private static final long MAX_RETRY_WAIT_SECONDS = 3600L;

    // 재시도 대기 시간 결정: Retry-After(초) → X-RateLimit-Reset(리셋까지 남은 초) → 60초, 상한 1시간.
    // secondary limit은 Retry-After를 주지만, primary limit 소진 403은 Retry-After 없이
    // X-RateLimit-Reset으로만 알려온다 — 60초 고정 폴백이면 리셋 전 재시도 3회를 소진하고
    // 실행 진행분을 통째로 버리게 되므로 reset 기준으로 실제 대기 시간을 맞춘다.
    static long resolveRetryWaitSeconds(HttpHeaders headers) {
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter != null) {
            return Math.min(MAX_RETRY_WAIT_SECONDS, parseRetryAfterSeconds(retryAfter));
        }
        String resetStr = headers.getFirst("X-RateLimit-Reset");
        if (resetStr != null) {
            try {
                long resetEpoch = Long.parseLong(resetStr);
                return Math.min(MAX_RETRY_WAIT_SECONDS,
                        Math.max(0, resetEpoch - System.currentTimeMillis() / 1000 + 1));
            } catch (NumberFormatException ignored) {
                // 비정상 reset은 아래 60초 폴백으로
            }
        }
        return 60L;
    }

    // Retry-After는 정수 초 문자열. 헤더가 없거나 형식이 어긋나면 60초로 보수적 폴백한다(SlackRawService와 동일 기준).
    static long parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null) {
            return 60L;
        }
        try {
            long seconds = Long.parseLong(headerValue);
            return seconds >= 0 ? seconds : 60L;
        } catch (NumberFormatException e) {
            return 60L;
        }
    }
}
