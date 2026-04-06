package com.history.pipeline_worker.checkpoint;

import java.time.Instant;

public class CheckpointData {

    public GitHubCheckpoint github = new GitHubCheckpoint();
    public SourceCheckpoint slack = new SourceCheckpoint();
    public SourceCheckpoint jira = new SourceCheckpoint();

    public static class GitHubCheckpoint {
        public Instant lastScannedAt;  // null = 최초 실행
    }

    public static class SourceCheckpoint {
        public Instant lastScannedAt;  // null = 최초 실행
    }
}
