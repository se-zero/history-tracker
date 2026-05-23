package com.history.backend.conversation.service;

import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;

public record ConversationStart(
        Conversation conversation,
        Message userMessage,
        Message assistantMessage
) {
}
