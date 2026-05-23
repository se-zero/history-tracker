package com.history.backend.conversation.dto;

import com.history.backend.conversation.service.MessageExchange;

public record MessageExchangeResponse(
        MessageResponse userMessage,
        MessageResponse assistantMessage
) {

    public static MessageExchangeResponse from(MessageExchange messageExchange) {
        return new MessageExchangeResponse(
                MessageResponse.from(messageExchange.userMessage()),
                MessageResponse.from(messageExchange.assistantMessage())
        );
    }
}
