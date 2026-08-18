package com.history.backend.conversation.dto;

import java.util.List;

import com.history.backend.graph.dto.EvidenceRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMessageRequest(
        @NotBlank
        @Size(max = 1000)
        String content,

        // 관련 그래프에서 지정한 focus 노드(도메인 키). 없으면 null — ai-engine 경계에서 빈 리스트로 정규화.
        @Size(max = 10)
        List<EvidenceRef> focusEvidence
) {
}
