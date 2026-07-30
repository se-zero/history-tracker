package com.history.backend.integration.service;

import java.util.UUID;

// OAuth 콜백 처리 결과 — 프론트 복귀 URL 조립에 필요한 최소 정보.
// errorCode가 null이면 성공, projectId는 state 검증에 실패한 경우에만 null이다.
public record OAuthCallbackOutcome(
        UUID projectId,
        String provider,
        String errorCode
) {
}
