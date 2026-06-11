package com.history.backend.conversation.service;

import org.springframework.stereotype.Component;

@Component
public class ConversationTitleGenerator {

    private static final int MAX_TITLE_LENGTH = 80;

    // 첫 메시지 기반 대화 제목 생성 (공백 정리 후 80자 제한)
    public String fromFirstMessage(String content) {
        String title = normalize(content);
        if (title.codePointCount(0, title.length()) <= MAX_TITLE_LENGTH) {
            return title;
        }
        // 이모지 등 서로게이트 쌍이 깨지지 않도록 code point 단위로 절단
        int endIndex = title.offsetByCodePoints(0, MAX_TITLE_LENGTH);
        return title.substring(0, endIndex);
    }

    private String normalize(String content) {
        return content.trim().replaceAll("\\s+", " ");
    }
}
