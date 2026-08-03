package com.history.backend.integration.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

// ai-engine GET /privacy/jira/accounts 응답 1건 — 보고 주기가 도래한 accountId.
public record PrivacyDueAccount(
        @JsonProperty("account_id") String accountId,
        @JsonProperty("updated_at") Instant updatedAt
) {
}
