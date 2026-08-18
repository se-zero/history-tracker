package com.history.backend.graph.dto;

// ai-engine이 Actor 하나에 연결된 소스별 표시 이름 1건 — erased는 탈퇴/접근 상실 등으로
// 원본 이름이 지워진 경우 null / "closed" / "access_lost"로 사유를 담는다.
public record ActorSourceName(String source, String name, String erased) {
}
