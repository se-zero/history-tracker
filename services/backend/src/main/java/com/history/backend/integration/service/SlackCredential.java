package com.history.backend.integration.service;

import com.fasterxml.jackson.annotation.JsonProperty;

// Slack OAuth 자격증명 — encrypted_credential(BYTEA)에 JSON으로 직렬화해 저장한다.
// worker의 SlackCredentialCodec.userToken()이 읽는 키 이름(user_token/bot_token)과 맞춘다.
public record SlackCredential(
        @JsonProperty("user_token") String userToken,
        @JsonProperty("bot_token") String botToken
) {
}
