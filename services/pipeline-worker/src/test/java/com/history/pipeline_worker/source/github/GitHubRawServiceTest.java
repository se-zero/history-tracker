package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
                new GitHubRateLimiter(0, 0)
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
                new GitHubRateLimiter(0, 0)
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
                new GitHubRateLimiter(0, 0)
        );

        service.fetchSample(new RawFetchRequest("Bearer token", "owner/repo", Map.of()));

        assertThat(capturedCommitsQuery.get()).doesNotContain("sha=");
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
