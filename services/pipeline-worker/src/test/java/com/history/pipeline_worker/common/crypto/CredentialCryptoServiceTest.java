package com.history.pipeline_worker.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCryptoServiceTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("12345678901234567890123456789012".getBytes());

    @Test
    void decrypt_returnsOriginalPlaintext() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        byte[] encrypted = service.encrypt("Bearer token-123");

        assertThat(service.decrypt(encrypted)).isEqualTo("Bearer token-123");
    }

    @Test
    void constructor_rejectsInvalidKeyLength() {
        String invalidKey = Base64.getEncoder().encodeToString("short".getBytes());

        assertThatThrownBy(() -> new CredentialCryptoService(new CredentialCryptoProperties(invalidKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("security.credentials.key must decode to 32 bytes.");
    }

    @Test
    void decrypt_rejectsInvalidPayload() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        assertThatThrownBy(() -> service.decrypt(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encrypted credential is invalid.");
    }
}
