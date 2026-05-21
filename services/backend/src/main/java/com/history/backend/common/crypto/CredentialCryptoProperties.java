package com.history.backend.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.credentials")
public record CredentialCryptoProperties(
        String key
) {
}
