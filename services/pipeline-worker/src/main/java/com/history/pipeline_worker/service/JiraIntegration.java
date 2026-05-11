package com.history.pipeline_worker.service;

public record JiraIntegration(
        String credentials,
        String projectKey,
        String baseUrl
) {}
