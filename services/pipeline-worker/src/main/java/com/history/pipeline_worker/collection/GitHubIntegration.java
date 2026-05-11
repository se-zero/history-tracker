package com.history.pipeline_worker.collection;

public record GitHubIntegration(
        String credentials,
        String repositoryFullName,
        String branch
) {}
