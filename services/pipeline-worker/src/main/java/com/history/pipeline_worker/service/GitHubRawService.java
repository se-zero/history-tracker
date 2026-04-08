package com.history.pipeline_worker.service;

import com.history.pipeline_worker.checkpoint.CheckpointData;
import com.history.pipeline_worker.checkpoint.FileCheckpointManager;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.ratelimit.GitHubRateLimiter;
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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class GitHubRawService {

    private static final int PER_PAGE = 100; // GitHub API 최대값

    private final WebClient webClient;
    private final GitHubRateLimiter rateLimiter;
    private final FileCheckpointManager checkpointManager;

    public GitHubRawService(
            WebClient.Builder webClientBuilder,
            @Value("${app.github.base-url}") String baseUrl,
            GitHubRateLimiter rateLimiter,
            FileCheckpointManager checkpointManager
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.rateLimiter = rateLimiter;
        this.checkpointManager = checkpointManager;
    }

    public Map<String, Object> fetch(RawFetchRequest request) {
        String[] parts = request.projectKey().split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("projectKey must be in 'owner/repo' format");
        }
        String owner = parts[0];
        String repo = parts[1];
        String auth = request.credentials();

        CheckpointData.GitHubCheckpoint cp = checkpointManager.getCached().github;

        // 타입별 독립 체크포인트 — 재시작 시 완료된 타입은 건너뜀
        List<Object> rawCommits = fetchAllPages(
                auth, "/repos/{owner}/{repo}/commits?per_page=" + PER_PAGE, owner, repo,
                cp.commitsScannedAt, "commit.author.date");
        List<Object> commits = enrichCommits(auth, rawCommits, owner, repo);

        List<Object> pullRequests = fetchAllPages(
                auth, "/repos/{owner}/{repo}/pulls?state=all&per_page=" + PER_PAGE, owner, repo,
                cp.pullRequestsScannedAt, "created_at");

        List<Object> issues = fetchAllPages(
                auth, "/repos/{owner}/{repo}/issues?state=all&per_page=" + PER_PAGE, owner, repo,
                cp.issuesScannedAt, "created_at");

        log.info("GitHub 수집 완료: commits={}, PRs={}, issues={}", commits.size(), pullRequests.size(), issues.size());

        return Map.of(
                "commits", commits,
                "pullRequests", pullRequests,
                "issues", issues
        );
    }

    /**
     * 전체 페이지를 순회하며 항목 수집.
     * GitHub는 최신순으로 반환하므로 lastScannedAt 이전 항목이 나오면 조기 종료.
     * lastScannedAt이 null(최초 실행)이면 전체 수집.
     */
    @SuppressWarnings("unchecked")
    private List<Object> fetchAllPages(String auth, String basePath, String owner, String repo,
                                        Instant lastScannedAt, String dateField) {
        List<Object> allItems = new ArrayList<>();
        int page = 1;

        while (true) {
            List<Object> pageItems = fetchPage(auth, basePath + "&page=" + page, owner, repo);
            if (pageItems.isEmpty()) break;

            for (Object item : pageItems) {
                if (lastScannedAt != null) {
                    String dateStr = extractNestedStr((Map<String, Object>) item, dateField);
                    if (dateStr != null) {
                        try {
                            if (!Instant.parse(dateStr).isAfter(lastScannedAt)) {
                                // 최신순 정렬 → 이후 항목도 모두 checkpoint 이전 → 조기 종료
                                log.debug("체크포인트 도달 (page={}, date={}), 수집 종료", page, dateStr);
                                return allItems;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                allItems.add(item);
            }

            if (pageItems.size() < PER_PAGE) break; // 마지막 페이지
            page++;
        }

        return allItems;
    }

    @SuppressWarnings("unchecked")
    private List<Object> enrichCommits(String auth, List<Object> commits, String owner, String repo) {
        List<Object> result = new ArrayList<>();
        for (Object raw : commits) {
            Map<String, Object> commit = new HashMap<>((Map<String, Object>) raw);
            String sha = (String) commit.get("sha");
            Map<String, Object> detail = fetchCommitDetail(auth, owner, repo, sha);
            if (detail != null) {
                commit.put("files", detail.get("files"));
            }
            result.add(commit);
        }
        return result;
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
