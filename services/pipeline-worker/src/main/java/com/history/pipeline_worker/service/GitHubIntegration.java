package com.history.pipeline_worker.service;

public record GitHubIntegration(
        String credentials,
        String repositoryFullName,
        String branch
) {}
