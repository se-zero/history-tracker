package com.history.backend.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.springframework.stereotype.Component;

// SlackCredential(user_token/bot_token) ↔ encrypted_credential(BYTEA) 변환.
// worker의 SlackCredentialCodec과 같은 정신 — 파싱 실패·루트 비object는 레거시 평문 폴백,
// JSON object인데 user_token이 없거나 blank/null이면 IllegalStateException.
@Component
public class SlackCredentialCodec {

    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SlackCredentialCodec(CredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = credentialCryptoService;
    }

    public byte[] encrypt(SlackCredential credential) {
        return credentialCryptoService.encrypt(serialize(credential));
    }

    // 연결 시점의 암호화는 IntegrationService가 공통으로 처리 — 여기서는 직렬화만
    public String serialize(SlackCredential credential) {
        try {
            return objectMapper.writeValueAsString(credential);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Slack credential.", exception);
        }
    }

    // Notion과 달리 평문 폴백이 있다 — 레거시 연동(평문 xoxp- 토큰)을 마이그레이션 없이 읽어야 하기 때문이다.
    // worker의 userToken() 로직과 동일하게, 파싱 실패 또는 루트가 object가 아닌 경우에만 폴백한다.
    public SlackCredential decrypt(byte[] encryptedCredential) {
        String plaintext = credentialCryptoService.decrypt(encryptedCredential);
        JsonNode root;
        try {
            root = objectMapper.readTree(plaintext);
        } catch (Exception e) {
            // JSON 파싱 실패 — 전체 평문을 userToken으로 폴백 (레거시)
            return new SlackCredential(plaintext, null);
        }
        if (!root.isObject()) {
            // 루트가 object가 아닌 경우도 평문 폴백 — worker와 동일 처리
            return new SlackCredential(plaintext, null);
        }
        // JSON object인 경우 user_token은 필수 — 없거나 blank면 저장된 자격증명이 깨진 것이다
        JsonNode userTokenNode = root.get("user_token");
        if (userTokenNode == null || !userTokenNode.isTextual() || userTokenNode.asText().isBlank()) {
            throw new IllegalStateException("Missing Slack credential field: user_token");
        }
        JsonNode botTokenNode = root.get("bot_token");
        String botToken = (botTokenNode != null && botTokenNode.isTextual() && !botTokenNode.asText().isBlank())
                ? botTokenNode.asText() : null;
        return new SlackCredential(userTokenNode.asText(), botToken);
    }
}
