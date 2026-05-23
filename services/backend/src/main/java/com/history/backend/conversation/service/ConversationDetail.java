package com.history.backend.conversation.service;

import java.util.List;

import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;

public record ConversationDetail(
        Conversation conversation,
        List<Message> messages
) {
}
