package com.history.backend.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// POST /privacy/jira/accounts/apply 요청의 erase 항목 — closed 또는 access_lost로 개인정보를 지운다.
public record EraseAccount(
        @JsonProperty("account_id") String accountId,
        String reason
) {
}
