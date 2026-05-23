package com.history.backend.conversation.dto;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.conversation.domain.Conversation;

public record ConversationResponse(
        UUID id,
        UUID projectId,
        UUID userId,
        String title,
        Instant createdAt,
        Instant updatedAt
) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getProject().getId(),
                conversation.getUser() == null ? null : conversation.getUser().getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
