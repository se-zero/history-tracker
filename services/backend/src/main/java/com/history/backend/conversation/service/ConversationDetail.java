package com.history.backend.conversation.service;

import com.history.backend.conversation.domain.Conversation;

public record ConversationDetail(
        Conversation conversation,
        MessagePage messages
) {
}
