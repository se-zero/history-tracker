package com.history.backend.conversation.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.history.backend.conversation.domain.Message;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        String role,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getRole().name(),
                message.getContent(),
                message.getMetadata(),
                message.getCreatedAt()
        );
    }
}
