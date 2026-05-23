package com.history.backend.conversation.dto;

import jakarta.validation.constraints.Size;

public record UpdateConversationRequest(
        @Size(max = 200)
        String title
) {
}
