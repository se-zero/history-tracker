package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
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
    public List<NormalizedEvent> normalizeCommits(String projectId, List<Object> commits) {
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

            String gitConfigName = authorDetail != null ? (String) authorDetail.get("name") : null;
            String message = (String) commitDetail.get("message");
            String authoredAt = authorDetail != null ? (String) authorDetail.get("date") : null;
            Map<String, Object> committerDetail = (Map<String, Object>) commitDetail.get("committer");
            String committedAt = committerDetail != null ? (String) committerDetail.get("date") : null;

            // 신원은 GitHub 계정 기준으로 통일한다. GitHub 계정이 없으면 git config 이름만 폴백으로
            // 쓰고, git config 이메일은 협업 툴 계정이 아닌 개인 정보라 절대 사용하지 않는다.
            ActorDto actor = ghAuthor != null
                    ? new ActorDto((String) ghAuthor.get("login"), resolveDisplayName(ghAuthor), (String) ghAuthor.get("email"))
                    : new ActorDto(gitConfigName, gitConfigName, null);

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

            Map<String, Object> refs = refsExtractor.extract(message);
            Object prNumber = commit.get("prNumber");
            if (prNumber != null) {
                refs.put("prNumber", String.valueOf(prNumber));
            }

            events.add(new NormalizedEvent(
                    projectId,
                    "ChangeSet",
                    "GITHUB",
                    resolveInstant(committedAt, authoredAt),
                    actor,
                    properties,
                    refs
            ));
        }
        return events;
    }

    // pull requests → PullRequest 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizePullRequests(String projectId, List<Object> pullRequests) {
        List<NormalizedEvent> events = new ArrayList<>();
        for (Object raw : pullRequests) {
            Map<String, Object> pr = (Map<String, Object>) raw;
            Map<String, Object> user = (Map<String, Object>) pr.get("user");
            Map<String, Object> base = (Map<String, Object>) pr.get("base");

            String createdAt = (String) pr.get("created_at");
            String mergedAt = (String) pr.get("merged_at");
            String body = (String) pr.get("body");

            Map<String, Object> properties = new HashMap<>();
            properties.put("pr_number", pr.get("number"));
            properties.put("title", pr.get("title"));
            properties.put("body", body);
            properties.put("state", pr.get("state"));
            properties.put("base_branch", base != null ? base.get("ref") : null);
            properties.put("created_at", createdAt);
            properties.put("url", pr.get("html_url"));

            String content = (body != null ? body : "") + " " + pr.get("title");

            events.add(new NormalizedEvent(
                    projectId,
                    "PullRequest",
                    "GITHUB",
                    resolveInstant(mergedAt, createdAt),
                    new ActorDto(
                            user != null ? (String) user.get("login") : null,
                            user != null ? resolveDisplayName(user) : null,
                            user != null ? (String) user.get("email") : null
                    ),
                    properties,
                    refsExtractor.extract(content)
            ));
        }
        return events;
    }

    // issues (PR 제외) → Issue 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeIssues(String projectId, List<Object> issues) {
        List<NormalizedEvent> events = new ArrayList<>();
        for (Object raw : issues) {
            Map<String, Object> issue = (Map<String, Object>) raw;

            // pull_request 키가 있으면 PR — 이미 normalizePullRequests에서 처리
            if (issue.containsKey("pull_request")) continue;

            // external_id(id)는 이벤트 식별의 필수값이라, 없는 이슈는 건너뛴다 (AsanaNormalizer와 동일 규약).
            Object id = issue.get("id");
            if (id == null) continue;

            Map<String, Object> user = (Map<String, Object>) issue.get("user");
            String createdAt = (String) issue.get("created_at");
            String updatedAt = (String) issue.get("updated_at");
            String title = (String) issue.get("title");
            String body = (String) issue.get("body");

            String state = (String) issue.get("state");
            boolean closed = "closed".equals(state);
            String statusCategory = closed ? "closed" : "open";
            // closed면 state_reason(완료/취소 등 사유)을 status로, 없으면 "closed"로 폴백.
            // open이면 사유 개념이 없으니 항상 "open"으로 고정한다.
            String status = closed
                    ? firstNonNull((String) issue.get("state_reason"), "closed")
                    : "open";

            Map<String, Object> properties = new HashMap<>();
            properties.put("external_id", String.valueOf(id));
            properties.put("issue_key", "#" + issue.get("number"));
            properties.put("title", title != null ? title : "");
            properties.put("body", body != null ? body : "");
            properties.put("status_category", statusCategory);
            properties.put("status", status);
            properties.put("created_at", createdAt);
            // 종료된 이슈만 closed_at을 채운다 — 미종료(재오픈 포함)에서 키를 생략해야
            // ai-engine builder가 status_category를 보고 closedAt을 null로 클리어한다 (Linear와 동일 규약).
            if (closed) {
                properties.put("closed_at", (String) issue.get("closed_at"));
            }

            events.add(new NormalizedEvent(
                    projectId,
                    "Issue",
                    "GITHUB",
                    resolveInstant(updatedAt, createdAt),
                    new ActorDto(
                            user != null ? (String) user.get("login") : null,
                            user != null ? resolveDisplayName(user) : null,
                            user != null ? (String) user.get("email") : null
                    ),
                    properties,
                    resolveIssueRefs(title, body, issue)
            ));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveIssueRefs(String title, String body, Map<String, Object> issue) {
        Map<String, Object> refs = new HashMap<>(
                refsExtractor.extract((title != null ? title : "") + "\n\n" + (body != null ? body : "")));

        // Issue는 최신 스냅샷이라 담당자 없음도 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다.
        List<Object> assignees = (List<Object>) issue.get("assignees");
        List<Map<String, Object>> resolvedAssignees = new ArrayList<>();
        if (assignees != null) {
            for (Object raw : assignees) {
                Map<String, Object> assignee = (Map<String, Object>) raw;
                String login = (String) assignee.get("login");
                if (login == null) continue;
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", login);
                entry.put("name", resolveDisplayName(assignee));
                entry.put("email", assignee.get("email"));
                resolvedAssignees.add(entry);
            }
        }
        refs.put("assignees", resolvedAssignees);

        return refs;
    }

    private String firstNonNull(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }

    /** enrichUserObjects가 name을 보강했으면 사용, 없으면 login으로 대체 */
    private String resolveDisplayName(Map<String, Object> user) {
        String name = (String) user.get("name");
        return (name != null && !name.isBlank()) ? name : (String) user.get("login");
    }

    private Instant resolveInstant(String primary, String fallback) {
        if (primary != null) return Instant.parse(primary);
        if (fallback != null) return Instant.parse(fallback);
        return Instant.now();
    }
}
