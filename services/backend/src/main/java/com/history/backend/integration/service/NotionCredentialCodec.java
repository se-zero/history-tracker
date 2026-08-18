package com.history.backend.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.springframework.stereotype.Component;

// NotionCredential(access/refresh token) ↔ encrypted_credential(BYTEA) 변환.
// GoogleChatCredentialCodec과 같은 형태 — expiresAt만 없다(Notion 갱신 응답에 만료 정보가 없다).
@Component
public class NotionCredentialCodec {

    private final CredentialCryptoService credentialCryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotionCredentialCodec(CredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = credentialCryptoService;
    }

    public byte[] encrypt(NotionCredential credential) {
        return credentialCryptoService.encrypt(serialize(credential));
    }

    // 암호화 없이 직렬화만 — 연결 시점의 암호화는 IntegrationService가 provider 공통으로 처리한다
    public String serialize(NotionCredential credential) {
        try {
            return objectMapper.writeValueAsString(credential);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Notion credential.", exception);
        }
    }

    // 역직렬화 실패를 IllegalStateException으로 변환해 호출부가 일관되게 다루도록 한다
    public NotionCredential decrypt(byte[] encryptedCredential) {
        String json = credentialCryptoService.decrypt(encryptedCredential);
        try {
            return objectMapper.readValue(json, NotionCredential.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize Notion credential.", exception);
        }
    }
}
