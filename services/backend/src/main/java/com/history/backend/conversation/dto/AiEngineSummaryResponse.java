package com.history.backend.conversation.dto;

import java.util.Map;

public record AiEngineSummaryResponse(
        Map<String, Object> summary
) {
}
