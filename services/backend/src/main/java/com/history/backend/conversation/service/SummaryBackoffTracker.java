package com.history.backend.conversation.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.history.backend.conversation.ChatMemoryProperties;
import org.springframework.stereotype.Component;

// 요약(running summary) 연속 실패에 따른 인메모리 스킵 백오프.
// 상태는 인스턴스 로컬이며 재시작 시 리셋된다 — 여러 인스턴스·재시작 간 공유하지 않는 설계다.
// running summary 갱신 자체가 version 기반 CAS(ConversationRepository.updateRunningSummary)로
// 보호되므로, 백오프 상태가 리셋돼 재시도가 몰리거나 인스턴스마다 스킵 판단이 달라도
// 중복 갱신이 발생하지 않아 안전하다.
@Component
public class SummaryBackoffTracker {

    private final ChatMemoryProperties chatMemoryProperties;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public SummaryBackoffTracker(ChatMemoryProperties chatMemoryProperties) {
        this.chatMemoryProperties = chatMemoryProperties;
    }

    // 남은 스킵 횟수가 있으면 원자적으로 1 소진하고 true, 없으면(또는 기록 없음) false
    public boolean shouldSkip(UUID conversationId) {
        boolean[] skipped = {false};
        states.computeIfPresent(conversationId, (id, state) -> {
            if (state.remainingSkips() <= 0) {
                return state;
            }
            skipped[0] = true;
            return new State(state.consecutiveFailures(), state.remainingSkips() - 1);
        });
        return skipped[0];
    }

    // 연속 실패가 임계에 도달하면 스킵 횟수를 세팅한다.
    // 임계를 넘긴 뒤 또 실패해도 재세팅되어 반복적으로 백오프가 걸린다.
    public void recordFailure(UUID conversationId) {
        states.compute(conversationId, (id, state) -> {
            int consecutiveFailures = (state == null ? 0 : state.consecutiveFailures()) + 1;
            int remainingSkips = state == null ? 0 : state.remainingSkips();
            if (consecutiveFailures >= chatMemoryProperties.summaryFailureThreshold()) {
                remainingSkips = chatMemoryProperties.summarySkipTurns();
            }
            return new State(consecutiveFailures, remainingSkips);
        });
    }

    public void recordSuccess(UUID conversationId) {
        states.remove(conversationId);
    }

    private record State(int consecutiveFailures, int remainingSkips) {
    }
}
