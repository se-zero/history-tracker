package com.history.backend.conversation.service;

import java.util.Map;

// reason은 MCP 도구가 "N초 안에 답을 못 만들었다"와 "오류"를 다르게 안내하기 위한 것이다.
// 기존 호출자는 fallback()만 본다.
public record AiEngineQueryResult(
        String answer,
        boolean fallback,
        Map<String, Object> structured,
        FallbackReason reason
) {

    public enum FallbackReason { NONE, TIMEOUT, ERROR }

    public static AiEngineQueryResult success(String answer, Map<String, Object> structured) {
        return new AiEngineQueryResult(answer, false, structured, FallbackReason.NONE);
    }

    public static AiEngineQueryResult fallback(String answer) {
        return new AiEngineQueryResult(answer, true, null, FallbackReason.ERROR);
    }

    public static AiEngineQueryResult timeout(String answer) {
        return new AiEngineQueryResult(answer, true, null, FallbackReason.TIMEOUT);
    }
}
