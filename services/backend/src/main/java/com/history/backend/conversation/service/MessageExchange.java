package com.history.backend.conversation.service;

import com.history.backend.conversation.domain.Message;

public record MessageExchange(
        Message userMessage,
        Message assistantMessage
) {
}
