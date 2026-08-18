package com.history.backend.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 자격증명 암호화 키 설정
@ConfigurationProperties(prefix = "security.credentials")
public record CredentialCryptoProperties(
        String key
) {
}
