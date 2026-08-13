package com.history.backend.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.springframework.stereotype.Component;

// ClickUpCredential(access token) ↔ encrypted_credential(BYTEA) 변환.
// AsanaCredentialCodec과 동일한 패턴 — JSON 직렬화 후 기존 CredentialCryptoService(AES-GCM)를 그대로 감싼다.
@Component
public class ClickUpCredentialCodec {

    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClickUpCredentialCodec(CredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = credentialCryptoService;
    }

    // 암호화 없이 직렬화만 — 연결 시점의 암호화는 IntegrationService가 provider 공통으로 처리한다
    public String serialize(ClickUpCredential credential) {
        try {
            return objectMapper.writeValueAsString(credential);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize ClickUp credential.", exception);
        }
    }

    // 역직렬화 실패를 IllegalStateException으로 변환해 호출부가 일관되게 다루도록 한다
    public ClickUpCredential decrypt(byte[] encryptedCredential) {
        String json = credentialCryptoService.decrypt(encryptedCredential);
        try {
            return objectMapper.readValue(json, ClickUpCredential.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize ClickUp credential.", exception);
        }
    }
}
