package com.history.backend.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class CredentialCryptoServiceTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("test-credential-key-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptAndDecryptCredential() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        byte[] encrypted = service.encrypt("Bearer secret-token");

        assertThat(encrypted).isNotEmpty();
        assertThat(Base64.getEncoder().encodeToString(encrypted)).doesNotContain("secret-token");
        assertThat(service.decrypt(encrypted)).isEqualTo("Bearer secret-token");
    }

    @Test
    void encryptUsesDifferentIvForSamePlaintext() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        byte[] first = service.encrypt("Bearer secret-token");
        byte[] second = service.encrypt("Bearer secret-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("Bearer secret-token");
        assertThat(service.decrypt(second)).isEqualTo("Bearer secret-token");
    }

    @Test
    void decryptRejectsTamperedCiphertext() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));
        byte[] encrypted = service.encrypt("Bearer secret-token");
        byte[] tampered = Arrays.copyOf(encrypted, encrypted.length);
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encrypted credential is invalid.");
    }

    @Test
    void decryptRejectsInputShorterThanIvAndTag() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        assertThatThrownBy(() -> service.decrypt(new byte[27]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encrypted credential is invalid.");
    }

    @Test
    void constructorRejectsInvalidKeyLength() {
        String invalidKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new CredentialCryptoService(new CredentialCryptoProperties(invalidKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("security.credentials.key must decode to 32 bytes.");
    }

    @Test
    void encryptRejectsBlankPlaintext() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        assertThatThrownBy(() -> service.encrypt(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Credential plaintext must not be blank.");
    }
}
