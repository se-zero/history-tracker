package com.history.backend.clickup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// ClickUp 토큰 교환 요청 — Asana·Linear와 달리 form-urlencoded가 아니라 JSON body를 받는다
public record ClickUpTokenRequest(
        @JsonProperty("client_id")
        String clientId,

        @JsonProperty("client_secret")
        String clientSecret,

        String code
) {
}
