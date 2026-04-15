package com.history.pipeline_worker.normalizer;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GitHubNormalizer {

    private final RefsExtractor refsExtractor;

    // commits → ChangeSet 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeCommits(List<Object> commits) {
        List<NormalizedEvent> events = new ArrayList<>();
        for (Object raw : commits) {
            Map<String, Object> commit = (Map<String, Object>) raw;

            // merge commit 제외
            List<Object> parents = (List<Object>) commit.get("parents");
            if (parents != null && parents.size() > 1) continue;

            Map<String, Object> commitDetail = (Map<String, Object>) commit.get("commit");
            if (commitDetail == null) continue;

            Map<String, Object> authorDetail = (Map<String, Object>) commitDetail.get("author");
            Map<String, Object> ghAuthor = (Map<String, Object>) commit.get("author"); // GitHub 계정 (null 가능)

            String authorName = authorDetail != null ? (String) authorDetail.get("name") : null;
            String authorLogin = ghAuthor != null ? (String) ghAuthor.get("login") : authorName;
            String message = (String) commitDetail.get("message");
            String dateStr = authorDetail != null ? (String) authorDetail.get("date") : null;

            Map<String, Object> properties = new HashMap<>();
            List<Object> rawFiles = (List<Object>) commit.get("files");
            List<Map<String, Object>> files = new ArrayList<>();
            if (rawFiles != null) {
                for (Object f : rawFiles) {
                    Map<String, Object> fd = (Map<String, Object>) f;
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("path", fd.get("filename"));
                    entry.put("diff", fd.get("patch"));
                    entry.put("additions", fd.get("additions"));
                    entry.put("deletions", fd.get("deletions"));
                    files.add(entry);
                }
            }

            properties.put("hash", commit.get("sha"));
            properties.put("message", message);
            properties.put("files", files);
            events.add(new NormalizedEvent(
                    "ChangeSet",
                    "GITHUB",
                    dateStr != null ? Instant.parse(dateStr) : Instant.now(),
                    new ActorDto(authorLogin, authorName, null),
                    properties,
                    refsExtractor.extract(message)
            ));
        }
        return events;
    }

    // pull requests → PullRequest 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizePullRequests(List<Object> pullRequests) {
        List<NormalizedEvent> events = new ArrayList<>();
        for (Object raw : pullRequests) {
            Map<String, Object> pr = (Map<String, Object>) raw;
            Map<String, Object> user = (Map<String, Object>) pr.get("user");
            Map<String, Object> base = (Map<String, Object>) pr.get("base");

            String createdAt = (String) pr.get("created_at");
            String body = (String) pr.get("body");

            Map<String, Object> properties = new HashMap<>();
            properties.put("pr_number", pr.get("number"));
            properties.put("title", pr.get("title"));
            properties.put("body", body);
            properties.put("state", pr.get("state"));
            properties.put("base_branch", base != null ? base.get("ref") : null);
            properties.put("merged_at", pr.get("merged_at"));
            properties.put("url", pr.get("html_url"));

            String content = (body != null ? body : "") + " " + pr.get("title");

            events.add(new NormalizedEvent(
                    "PullRequest",
                    "GITHUB",
                    createdAt != null ? Instant.parse(createdAt) : Instant.now(),
                    new ActorDto(
                            user != null ? (String) user.get("login") : null,
                            user != null ? (String) user.get("login") : null,
                            null
                    ),
                    properties,
                    refsExtractor.extract(content)
            ));
        }
        return events;
    }

    // issues (PR 제외) → Communication 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeIssues(List<Object> issues) {
        List<NormalizedEvent> events = new ArrayList<>();
        for (Object raw : issues) {
            Map<String, Object> issue = (Map<String, Object>) raw;

            // pull_request 키가 있으면 PR — 이미 normalizePullRequests에서 처리
            if (issue.containsKey("pull_request")) continue;

            Map<String, Object> user = (Map<String, Object>) issue.get("user");
            String createdAt = (String) issue.get("created_at");
            String title = (String) issue.get("title");
            String body = (String) issue.get("body");

            // title을 body 앞에 합산 — GitHub issue는 title이 핵심 맥락
            String combinedBody = (title != null ? title : "") +
                    (body != null && !body.isBlank() ? "\n\n" + body : "");

            Map<String, Object> properties = new HashMap<>();
            properties.put("body", combinedBody);
            properties.put("channel", "github_issues");
            properties.put("url", issue.get("html_url"));
            properties.put("conversation_id", String.valueOf(issue.get("number")));

            String content = combinedBody;

            events.add(new NormalizedEvent(
                    "Communication",
                    "GITHUB",
                    createdAt != null ? Instant.parse(createdAt) : Instant.now(),
                    new ActorDto(
                            user != null ? (String) user.get("login") : null,
                            user != null ? (String) user.get("login") : null,
                            null
                    ),
                    properties,
                    refsExtractor.extract(content)
            ));
        }
        return events;
    }
}
