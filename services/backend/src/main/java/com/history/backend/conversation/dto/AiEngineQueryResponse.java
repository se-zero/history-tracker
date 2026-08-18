package com.history.backend.conversation.dto;

import java.util.Map;

public record AiEngineQueryResponse(
        String answer,
        Map<String, Object> structured
) {
}
