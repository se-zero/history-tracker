package com.history.backend.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.service.ConversationDetail;
import com.history.backend.conversation.service.ConversationStart;

public record ConversationDetailResponse(
        UUID id,
        UUID projectId,
        UUID userId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<MessageResponse> messages
) {

    public static ConversationDetailResponse from(Conversation conversation, List<Message> messages) {
        return new ConversationDetailResponse(
                conversation.getId(),
                conversation.getProject().getId(),
                conversation.getUser() == null ? null : conversation.getUser().getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                messages.stream()
                        .map(MessageResponse::from)
                        .toList()
        );
    }

    public static ConversationDetailResponse from(ConversationStart conversationStart) {
        return from(
                conversationStart.conversation(),
                List.of(conversationStart.userMessage(), conversationStart.assistantMessage())
        );
    }

    public static ConversationDetailResponse from(ConversationDetail conversationDetail) {
        return from(conversationDetail.conversation(), conversationDetail.messages());
    }
}
