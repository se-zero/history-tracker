package com.history.backend.conversation.service;

public record AiEngineQueryResult(
        String answer,
        boolean fallback
) {

    public static AiEngineQueryResult success(String answer) {
        return new AiEngineQueryResult(answer, false);
    }

    public static AiEngineQueryResult fallback(String answer) {
        return new AiEngineQueryResult(answer, true);
    }
}
