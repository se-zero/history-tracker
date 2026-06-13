package com.history.backend.conversation.service;

import java.util.Map;

public record AiEngineQueryResult(
        String answer,
        boolean fallback,
        Map<String, Object> structured
) {

    public static AiEngineQueryResult success(String answer, Map<String, Object> structured) {
        return new AiEngineQueryResult(answer, false, structured);
    }

    public static AiEngineQueryResult fallback(String answer) {
        return new AiEngineQueryResult(answer, true, null);
    }
}
