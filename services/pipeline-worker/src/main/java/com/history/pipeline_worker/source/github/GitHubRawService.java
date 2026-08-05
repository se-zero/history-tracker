package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class GitHubRawService {

    private static final int PER_PAGE = 100; // GitHub API 최대값

    public record GitHubFetchContext(String auth, String owner, String repo, String branch, GitHubCheckpoint checkpoint) {}
    public record GitHubPage(List<Object> items, boolean finished) {}

    private final WebClient webClient;
    private final GitHubRateLimiter rateLimiter;

    // login → {email, name} 캐시 — 동일 user에 대한 반복 API 호출 방지
    private final Map<String, CachedProfile> userProfileCache = new ConcurrentHashMap<>();
    private final Duration userProfileCacheTtl;

    private record CachedProfile(Map<String, String> profile, Instant fetchedAt) {}

    public GitHubRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.github.base-url}") String baseUrl,
            GitHubRateLimiter rateLimiter,
            @Value("${app.github.user-profile-cache-ttl:30m}") Duration userProfileCacheTtl
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.rateLimiter = rateLimiter;
        this.userProfileCacheTtl = userProfileCacheTtl;
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
        return new GitHubFetchContext(auth, owner, repo, branch, checkpoint);
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
        return new GitHubPage(enrichUserObjects(context.auth(), mergedPullRequests), closedPullRequests.finished());
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
                enrichCommits(context.auth(), rawCommits.items(), context.owner(), context.repo(), commitPrNumbers),
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
        return new GitHubPage(enrichUserObjects(context.auth(), issues.items()), issues.finished());
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

    /** commit.author(GitHub 계정)에 프로필 name/email 보강. login별 캐시로 기여자 수만큼만 호출한다. */
    @SuppressWarnings("unchecked")
    private List<Object> enrichCommits(String auth, List<Object> commits, String owner, String repo,
                                       Map<String, String> commitPrNumbers) {
        List<Object> result = new ArrayList<>();
        for (Object raw : commits) {
            Map<String, Object> commit = new HashMap<>((Map<String, Object>) raw);
            String sha = (String) commit.get("sha");
            Map<String, Object> detail = fetchCommitDetail(auth, owner, repo, sha);
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
                    Map<String, String> profile = fetchUserProfile(login, auth);
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
    private List<Object> enrichUserObjects(String auth, List<Object> items) {
        List<Object> result = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = new HashMap<>((Map<String, Object>) raw);
            Map<String, Object> user = (Map<String, Object>) item.get("user");
            if (user != null) {
                String login = (String) user.get("login");
                if (login != null) {
                    Map<String, String> profile = fetchUserProfile(login, auth);
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

    /** GET /users/{login} → {email, name}. login별로 TTL 동안 캐시를 재사용하고, 만료되면 재조회 후 캐시를 갱신한다. */
    @SuppressWarnings("unchecked")
    private Map<String, String> fetchUserProfile(String login, String auth) {
        CachedProfile cached = userProfileCache.get(login);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(userProfileCacheTtl) < 0) {
            return cached.profile();
        }

        // computeIfAbsent는 매핑 함수의 반환값을 무조건 캐시하므로 실패(에러 상태·예외) 케이스를
        // 걸러낼 수 없다. get으로 조회 후 미스/만료일 때만 호출하고 성공한 결과만 캐시에 반영하는 패턴으로
        // 바꿔, 일시 장애가 그 계정의 신원 보강을 재시작 전까지 영구히 결손시키지 않도록 한다.
        try {
            AtomicReference<org.springframework.http.HttpHeaders> headersRef = new AtomicReference<>();
            AtomicBoolean success = new AtomicBoolean(false);
            Map<String, Object> result = webClient.get()
                    .uri("/users/{login}", login)
                    .header("Authorization", auth)
                    .exchangeToMono(resp -> {
                        headersRef.set(resp.headers().asHttpHeaders());
                        if (!resp.statusCode().is2xxSuccessful()) {
                            return Mono.empty();
                        }
                        success.set(true);
                        return resp.bodyToMono(Map.class);
                    })
                    .block();
            rateLimiter.acquire(headersRef.get());
            if (!success.get()) return Map.of();

            Map<String, String> profile = new HashMap<>();
            if (result != null) {
                String email = (String) result.get("email");
                String name  = (String) result.get("name");
                if (email != null) profile.put("email", email);
                if (name  != null) profile.put("name",  name);
            }
            userProfileCache.put(login, new CachedProfile(profile, Instant.now()));
            return profile;
        } catch (Exception e) {
            // 프로필은 부가 데이터라 조회 실패로 커밋/이슈 수집 자체를 막지 않는다.
            log.warn("GitHub user profile 조회 실패 (login={}): {}", login, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCommitDetail(String auth, String owner, String repo, String sha) {
        AtomicReference<HttpHeaders> headersRef = new AtomicReference<>();
        Map<String, Object> result = webClient.get()
                .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                .header("Authorization", auth)
                .exchangeToMono(resp -> {
                    headersRef.set(resp.headers().asHttpHeaders());
                    return resp.bodyToMono(Map.class);
                })
                .block();
        rateLimiter.acquire(headersRef.get());
        return result;
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

    @SuppressWarnings("unchecked")
    private List<Object> fetchPage(String auth, String path, String owner, String repo) {
        AtomicReference<HttpHeaders> headersRef = new AtomicReference<>();
        List<Object> result = webClient.get()
                .uri(path, owner, repo)
                .header("Authorization", auth)
                .exchangeToMono(resp -> {
                    headersRef.set(resp.headers().asHttpHeaders());
                    return resp.bodyToMono(List.class);
                })
                .block();
        rateLimiter.acquire(headersRef.get());
        return result != null ? result : List.of();
    }

    /** "commit.author.date" 처럼 점(.) 구분 중첩 경로로 문자열 값 추출 */
    @SuppressWarnings("unchecked")
    private String extractNestedStr(Map<String, Object> map, String dotPath) {
        String[] parts = dotPath.split("\\.", 2);
        Object val = map.get(parts[0]);
        if (parts.length == 1) return val instanceof String ? (String) val : null;
        return val instanceof Map ? extractNestedStr((Map<String, Object>) val, parts[1]) : null;
    }
}
