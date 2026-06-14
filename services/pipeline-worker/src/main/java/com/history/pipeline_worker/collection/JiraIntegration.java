package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;

import java.util.HashMap;
import java.util.Map;

public record JiraIntegration(
        String credentials,
        String projectKey,
        String baseUrl
) {

    public RawFetchRequest toRawFetchRequest() {
        Map<String, String> options = new HashMap<>();
        if (baseUrl != null && !baseUrl.isBlank()) {
            options.put("baseUrl", baseUrl);
        }
        return new RawFetchRequest(credentials, projectKey, options);
    }
}
