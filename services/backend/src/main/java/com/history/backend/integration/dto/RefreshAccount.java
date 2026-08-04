package com.history.backend.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// POST /privacy/jira/accounts/apply 요청의 refresh 항목 — 재조회한 이름·이메일로 개인정보를 갱신한다.
// email은 비공개 계정이면 null일 수 있다.
public record RefreshAccount(
        @JsonProperty("account_id") String accountId,
        String name,
        String email
) {
}
