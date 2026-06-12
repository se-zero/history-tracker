package com.history.backend.conversation.dto;

import java.util.List;

public record AiEngineQueryRequest(
        String question,
        List<AiEngineHistoryMessage> history
) {
}
