package com.history.backend.conversation.service;

import java.util.List;

import com.history.backend.conversation.domain.Message;

public record MessagePage(
        List<Message> items,
        boolean hasMore,
        String nextCursor
) {
}
