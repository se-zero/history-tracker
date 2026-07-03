package com.history.backend.conversation.dto;

import java.util.List;

import com.history.backend.conversation.service.ConversationSearchResult;

public record ConversationSearchResponse(List<ConversationSearchItemResponse> items) {

    public static ConversationSearchResponse from(List<ConversationSearchResult> results) {
        return new ConversationSearchResponse(
                results.stream().map(ConversationSearchItemResponse::from).toList()
        );
    }
}
