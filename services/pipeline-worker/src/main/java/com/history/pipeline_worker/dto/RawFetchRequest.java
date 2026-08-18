package com.history.pipeline_worker.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

// 외부 API raw 데이터 수집 요청 DTO
public record RawFetchRequest(
        // 인증 토큰. GitHub·Jira·Slack 모두 "Bearer {token}" — 디버그용 raw 호출도 같다.
        @NotBlank
        String credentials,

        // 수집 대상 식별자. GitHub: "owner/repo", Jira: "프로젝트키"
        // Slack은 전체 채널을 자동 수집하므로 불필요 (null 가능)
        String projectKey,

        // 소스별 추가 옵션. Jira는 baseUrl 필수 — OAuth 수집은 cloudId 게이트웨이 URL
        // (예: "https://api.atlassian.com/ex/jira/{cloudId}"), 디버그 raw 호출은 테넌트 URL도 가능
        Map<String, String> options
) {}
