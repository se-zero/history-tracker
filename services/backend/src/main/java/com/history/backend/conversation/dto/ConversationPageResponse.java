package com.history.backend.conversation.dto;

import java.util.List;

import com.history.backend.conversation.service.ConversationPage;

public record ConversationPageResponse(
        List<ConversationResponse> items,
        String nextCursor
) {

    public static ConversationPageResponse from(ConversationPage page) {
        return new ConversationPageResponse(
                page.items().stream()
                        .map(ConversationResponse::from)
                        .toList(),
                page.nextCursor()
        );
    }
}
