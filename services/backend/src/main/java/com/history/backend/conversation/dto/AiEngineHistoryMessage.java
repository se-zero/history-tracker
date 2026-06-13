package com.history.backend.conversation.dto;

public record AiEngineHistoryMessage(
        String role,
        String content
) {
}
