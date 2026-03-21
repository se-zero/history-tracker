package com.history.pipeline_worker.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

// 외부 API raw 데이터 수집 요청 DTO
public record RawFetchRequest(
        // 인증 토큰. GitHub·Slack: "Bearer {token}", Jira: "email:apiToken" 또는 "Basic {base64}"
        @NotBlank
        String credentials,

        // 수집 대상 식별자. GitHub: "owner/repo", Jira: "프로젝트키"
        // Slack은 전체 채널을 자동 수집하므로 불필요 (null 가능)
        String projectKey,

        // 소스별 추가 옵션. Jira는 테넌트 URL 필수: {"baseUrl": "https://myco.atlassian.net"}
        Map<String, String> options
) {}
