package com.history.backend.integration.service;

import java.util.UUID;

// OAuth 콜백 처리 결과 — 프론트 복귀 URL 조립에 필요한 최소 정보.
// errorCode가 null이면 성공, projectId는 state 검증에 실패한 경우에만 null이다.
public record OAuthCallbackOutcome(
        UUID projectId,
        String provider,
        String errorCode,
        // Jira 자동 복원으로 사이트·프로젝트가 이미 확정됐는지 여부 — 프론트가 "선택 필요" 배너와
        // "복원 완료" 배너를 구분하는 데 쓴다. Slack과 에러 경로는 항상 false다.
        boolean confirmed
) {
}
