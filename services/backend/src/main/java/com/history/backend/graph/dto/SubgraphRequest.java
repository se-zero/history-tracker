package com.history.backend.graph.dto;

import java.util.List;

// 대화 화면 그래프 패널 요청 — 활성 답변의 evidence 목록. projectId는 경로에서 받는다.
public record SubgraphRequest(
        List<EvidenceRef> evidence
) {
}
