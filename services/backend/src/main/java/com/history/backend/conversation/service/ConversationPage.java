package com.history.backend.conversation.service;

import java.util.List;

import com.history.backend.conversation.domain.Conversation;

public record ConversationPage(
        List<Conversation> items,
        String nextCursor
) {
}
