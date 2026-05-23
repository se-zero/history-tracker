package com.history.backend.conversation.service;

import org.springframework.stereotype.Component;

@Component
public class ConversationTitleGenerator {

    private static final int MAX_TITLE_LENGTH = 80;

    public String fromFirstMessage(String content) {
        String title = normalize(content);
        if (title.codePointCount(0, title.length()) <= MAX_TITLE_LENGTH) {
            return title;
        }
        int endIndex = title.offsetByCodePoints(0, MAX_TITLE_LENGTH);
        return title.substring(0, endIndex);
    }

    private String normalize(String content) {
        return content.trim().replaceAll("\\s+", " ");
    }
}
