package com.history.pipeline_worker.collection;

public record JiraIntegration(
        String credentials,
        String projectKey,
        String baseUrl
) {}
