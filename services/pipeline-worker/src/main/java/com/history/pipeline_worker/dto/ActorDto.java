package com.history.pipeline_worker.dto;

// 이벤트를 발생시킨 사람. 소스마다 ID 형식이 다르며 alias 통합은 ai-engine이 담당
public record ActorDto(
        String id,    // 소스별 사용자 ID (GitHub: login, Jira: accountId, Slack: userId)
        String name,  // 표시 이름
        String email  // 이메일 (null 허용 — 수집 불가 시 ai-engine에서 빈 배열로 처리)
) {}
