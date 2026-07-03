package com.history.backend.conversation.dto;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.conversation.service.ConversationSearchResult;

// 통합 검색 결과 대화 1건. snippet은 매치된 메시지 발췌(제목만 매치면 null).
public record ConversationSearchItemResponse(
        UUID id,
        String title,
        String snippet,
        Instant updatedAt
) {

    public static ConversationSearchItemResponse from(ConversationSearchResult result) {
        return new ConversationSearchItemResponse(
                result.conversation().getId(),
                result.conversation().getTitle(),
                result.snippet(),
                result.conversation().getUpdatedAt()
        );
    }
}
