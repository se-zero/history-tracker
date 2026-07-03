package com.history.backend.conversation.service;

import com.history.backend.conversation.domain.Conversation;

// 통합 검색 결과 1건 — 대화 + 매치 스니펫 (제목만 매치면 snippet은 null)
public record ConversationSearchResult(Conversation conversation, String snippet) {
}
