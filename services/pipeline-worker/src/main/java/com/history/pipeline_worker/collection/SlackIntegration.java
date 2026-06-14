package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;

import java.util.Map;

public record SlackIntegration(
        String credentials
) {

    public RawFetchRequest toRawFetchRequest() {
        return new RawFetchRequest(credentials, null, Map.of());
    }
}
