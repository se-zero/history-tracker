package com.history.backend.auth.dto;

// installationId를 String으로 두는 이유: @RequestParam Long이면 비숫자 위조 값에 Spring 바인딩이
// 400을 내 로그인이 막힌다 — 파싱은 서비스에서 관대하게 한다.
public record GitHubCallbackRequest(
        String code,
        String state,
        String installationId
) {
}
