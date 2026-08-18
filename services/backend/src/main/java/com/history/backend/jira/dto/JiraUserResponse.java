package com.history.backend.jira.dto;

// GET .../rest/api/3/user 응답 — 조직 가시성 설정에 따라 emailAddress가 빠질 수 있다
public record JiraUserResponse(
        String accountId,
        String displayName,
        String emailAddress
) {
}
