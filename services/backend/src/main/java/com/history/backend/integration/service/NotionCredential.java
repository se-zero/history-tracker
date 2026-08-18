package com.history.backend.integration.service;

import com.fasterxml.jackson.annotation.JsonProperty;

// Notion OAuth 자격증명 — encrypted_credential(BYTEA)에 JSON으로 직렬화해 저장한다.
// refresh_token은 지금 갱신에 쓰이지 않지만(AccessTokenRefresher 미구현 — Notion 갱신 응답에
// expires_in이 없어 만료 임박 판정 자체가 불가능하다), 나중에 반응형 갱신을 붙일 때 마이그레이션
// 없이 쓸 수 있도록 자리를 미리 만들어 저장한다.
public record NotionCredential(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken
) {
}
