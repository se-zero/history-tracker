package com.history.backend.conversation.dto;

import java.util.List;

import com.history.backend.conversation.service.MessagePage;

public record MessagePageResponse(
        List<MessageResponse> items,
        boolean hasMore,
        String nextCursor
) {

    public static MessagePageResponse from(MessagePage page) {
        return new MessagePageResponse(
                page.items().stream()
                        .map(MessageResponse::from)
                        .toList(),
                page.hasMore(),
                page.nextCursor()
        );
    }
}
