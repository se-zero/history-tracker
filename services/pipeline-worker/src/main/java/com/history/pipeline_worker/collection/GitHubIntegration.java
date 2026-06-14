package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;

import java.util.HashMap;
import java.util.Map;

public record GitHubIntegration(
        String credentials,
        String repositoryFullName,
        String branch
) {

    public RawFetchRequest toRawFetchRequest() {
        Map<String, String> options = new HashMap<>();
        if (branch != null && !branch.isBlank()) {
            options.put("branch", branch);
        }
        return new RawFetchRequest(credentials, repositoryFullName, options);
    }
}
