package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.checkpoint.ProjectCheckpointData;
import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class GitHubRawService {

    private static final int PER_PAGE = 100; // GitHub API 최대값

    public record GitHubFetchContext(String auth, String owner, String repo, String branch, ProjectCheckpointData.GitHubCheckpoint checkpoint) {}
    public record GitHubPage(List<Object> items, boolean finished) {}

    private final WebClient webClient;
    private final GitHubRateLimiter rateLimiter;

    // login → {email, name} 캐시 — 동일 user에 대한 반복 API 호출 방지
    private final Map<String, Map<String, String>> userProfileCache = new ConcurrentHashMap<>();

    public GitHubRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.github.base-url}") String baseUrl,
            GitHubRateLimiter rateLimiter
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.rateLimiter = rateLimiter;
    }

    public GitHubFetchContext prepareFetchContext(
            RawFetchRequest request,
            ProjectCheckpointData.GitHubCheckpoint checkpoint
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
        GitHubFetchContext context = prepareFetchContext(request, new ProjectCheckpointData.GitHubCheckpoint());
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
                context.checkpoint().pullRequestsScannedAt,
                "updated_at",
                page
        );

        List<Object> mergedPullRequests = filterMergedPullRequests(
                closedPullRequests.items(),
                context.checkpoint().pullRequestsScannedAt
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
                context.checkpoint().commitsScannedAt,
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
                context.checkpoint().issuesScannedAt,
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

    /** GET /users/{login} → {email, name} (캐시 적용) */
    @SuppressWarnings("unchecked")
    private Map<String, String> fetchUserProfile(String login, String auth) {
        return userProfileCache.computeIfAbsent(login, l -> {
            AtomicReference<org.springframework.http.HttpHeaders> headersRef = new AtomicReference<>();
            Map<String, Object> result = webClient.get()
                    .uri("/users/{login}", l)
                    .header("Authorization", auth)
                    .exchangeToMono(resp -> {
                        headersRef.set(resp.headers().asHttpHeaders());
                        return resp.bodyToMono(Map.class);
                    })
                    .block();
            rateLimiter.acquire(headersRef.get());
            if (result == null) return Map.of();
            Map<String, String> profile = new HashMap<>();
            String email = (String) result.get("email");
            String name  = (String) result.get("name");
            if (email != null) profile.put("email", email);
            if (name  != null) profile.put("name",  name);
            return profile;
        });
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
