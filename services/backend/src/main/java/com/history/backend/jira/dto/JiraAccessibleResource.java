package com.history.backend.jira.dto;

// GET /oauth/token/accessible-resources 응답 1건 — id가 이후 API 게이트웨이 경로에 쓰이는 cloudId다
public record JiraAccessibleResource(
        String id,
        String name,
        String url
) {
}
