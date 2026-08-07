package com.history.backend.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.springframework.stereotype.Component;

// JiraCredential(access/refresh token·만료 시각) ↔ encrypted_credential(BYTEA) 변환.
// JSON 직렬화 후 기존 CredentialCryptoService(AES-GCM)를 그대로 감싼다 — 토큰 갱신도 이 코덱을 재사용한다.
@Component
public class JiraCredentialCodec {

    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public JiraCredentialCodec(CredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = credentialCryptoService;
    }

    public byte[] encrypt(JiraCredential credential) {
        return credentialCryptoService.encrypt(serialize(credential));
    }

    // 암호화 없이 직렬화만 — 연결 시점의 암호화는 IntegrationService가 provider 공통으로 처리한다
    public String serialize(JiraCredential credential) {
        try {
            return objectMapper.writeValueAsString(credential);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Jira credential.", exception);
        }
    }

    // 역직렬화 실패를 IllegalStateException으로 변환해 호출부가 일관되게 다루도록 한다
    public JiraCredential decrypt(byte[] encryptedCredential) {
        String json = credentialCryptoService.decrypt(encryptedCredential);
        try {
            return objectMapper.readValue(json, JiraCredential.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize Jira credential.", exception);
        }
    }
}
