package com.history.pipeline_worker.checkpoint;

import java.time.Instant;

public class ProjectCheckpointData {

    public GitHubCheckpoint github = new GitHubCheckpoint();
    public SourceCheckpoint slack = new SourceCheckpoint();
    public SourceCheckpoint jira = new SourceCheckpoint();

    public static class GitHubCheckpoint {
        public Instant commitsScannedAt;
        public Instant pullRequestsScannedAt;
        public Instant issuesScannedAt;
    }

    public static class SourceCheckpoint {
        public Instant lastScannedAt;
    }
}
