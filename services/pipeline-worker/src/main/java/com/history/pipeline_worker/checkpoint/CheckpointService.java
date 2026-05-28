package com.history.pipeline_worker.checkpoint;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class CheckpointService {

    private static final String PROVIDER_GITHUB = "github";
    private static final String PROVIDER_JIRA = "jira";
    private static final String PROVIDER_SLACK = "slack";

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
        update(projectId, PROVIDER_GITHUB, GITHUB_COMMITS, scannedAt);
    }

    public void updateGitHubPullRequests(String projectId, Instant scannedAt) {
        update(projectId, PROVIDER_GITHUB, GITHUB_PULL_REQUESTS, scannedAt);
    }

    public void updateGitHubIssues(String projectId, Instant scannedAt) {
        update(projectId, PROVIDER_GITHUB, GITHUB_ISSUES, scannedAt);
    }

    public void updateJira(String projectId, Instant scannedAt) {
        update(projectId, PROVIDER_JIRA, JIRA_UPDATED, scannedAt);
    }

    public void updateSlack(String projectId, Instant scannedAt) {
        update(projectId, PROVIDER_SLACK, SLACK_MESSAGES, scannedAt);
    }

    private void update(String projectId, String provider, String cursorKey, Instant cursorValue) {
        if (cursorValue != null) {
            repository.upsertCursor(UUID.fromString(projectId), provider, cursorKey, cursorValue);
        }
    }

    private void apply(ProjectCheckpointData data, CheckpointRepository.CheckpointRow row) {
        switch (row.provider()) {
            case PROVIDER_GITHUB -> applyGitHub(data.github, row);
            case PROVIDER_JIRA -> data.jira.lastScannedAt = row.cursorValue();
            case PROVIDER_SLACK -> data.slack.lastScannedAt = row.cursorValue();
            default -> {
                // Unknown providers are ignored so new DB rows do not break older workers.
            }
        }
    }

    private void applyGitHub(ProjectCheckpointData.GitHubCheckpoint github, CheckpointRepository.CheckpointRow row) {
        switch (row.cursorKey()) {
            case GITHUB_COMMITS -> github.commitsScannedAt = row.cursorValue();
            case GITHUB_PULL_REQUESTS -> github.pullRequestsScannedAt = row.cursorValue();
            case GITHUB_ISSUES -> github.issuesScannedAt = row.cursorValue();
            default -> {
                // Unknown cursor keys are ignored so new DB rows do not break older workers.
            }
        }
    }
}
