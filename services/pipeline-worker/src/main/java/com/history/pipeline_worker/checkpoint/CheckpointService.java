package com.history.pipeline_worker.checkpoint;

import com.history.pipeline_worker.collection.CollectionProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class CheckpointService {

    private static final String GITHUB_COMMITS = "github_commits";
    private static final String GITHUB_PULL_REQUESTS = "github_pull_requests";
    private static final String GITHUB_ISSUES = "github_issues";
    private static final String JIRA_UPDATED = "jira_updated";
    private static final String SLACK_MESSAGES = "slack_messages";

    private final CheckpointRepository repository;

    public CheckpointService(CheckpointRepository repository) {
        this.repository = repository;
    }

    public ProjectCheckpointData loadProjectCheckpoints(String projectId) {
        ProjectCheckpointData data = new ProjectCheckpointData();
        UUID parsedProjectId = UUID.fromString(projectId);
        for (CheckpointRepository.CheckpointRow row : repository.findAllByProjectId(parsedProjectId)) {
            apply(data, row);
        }
        return data;
    }

    public void updateGitHubCommits(String projectId, Instant scannedAt) {
        update(projectId, CollectionProvider.GITHUB.value(), GITHUB_COMMITS, scannedAt);
    }

    public void updateGitHubPullRequests(String projectId, Instant scannedAt) {
        update(projectId, CollectionProvider.GITHUB.value(), GITHUB_PULL_REQUESTS, scannedAt);
    }

    public void updateGitHubIssues(String projectId, Instant scannedAt) {
        update(projectId, CollectionProvider.GITHUB.value(), GITHUB_ISSUES, scannedAt);
    }

    public void updateJira(String projectId, Instant scannedAt) {
        update(projectId, CollectionProvider.JIRA.value(), JIRA_UPDATED, scannedAt);
    }

    public void updateSlack(String projectId, Instant scannedAt) {
        update(projectId, CollectionProvider.SLACK.value(), SLACK_MESSAGES, scannedAt);
    }

    private void update(String projectId, String provider, String cursorKey, Instant cursorValue) {
        if (cursorValue != null) {
            repository.upsertCursor(UUID.fromString(projectId), provider, cursorKey, cursorValue);
        }
    }

    private void apply(ProjectCheckpointData data, CheckpointRepository.CheckpointRow row) {
        // 미지원 provider row 건너뜀 — 예외 대신 무시
        CollectionProvider provider = CollectionProvider.find(row.provider()).orElse(null);
        if (provider == null) {
            return;
        }
        switch (provider) {
            case GITHUB -> applyGitHub(data.github, row);
            case JIRA -> data.jira.lastScannedAt = row.cursorValue();
            case SLACK -> data.slack.lastScannedAt = row.cursorValue();
        }
    }

    private void applyGitHub(ProjectCheckpointData.GitHubCheckpoint github, CheckpointRepository.CheckpointRow row) {
        switch (row.cursorKey()) {
            case GITHUB_COMMITS -> github.commitsScannedAt = row.cursorValue();
            case GITHUB_PULL_REQUESTS -> github.pullRequestsScannedAt = row.cursorValue();
            case GITHUB_ISSUES -> github.issuesScannedAt = row.cursorValue();
            default -> {
                // 미지원 cursor key 건너뜀 — 예외 대신 무시
            }
        }
    }
}
