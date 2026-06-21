package com.history.backend.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CredentialCryptoService: AES-GCM 자격증명 암복호화")
class CredentialCryptoServiceTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("test-credential-key-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("암호화 후 복호화 성공")
    void encryptAndDecryptCredential() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        byte[] encrypted = service.encrypt("Bearer secret-token");

        assertThat(encrypted).isNotEmpty();
        assertThat(Base64.getEncoder().encodeToString(encrypted)).doesNotContain("secret-token");
        assertThat(service.decrypt(encrypted)).isEqualTo("Bearer secret-token");
    }

    @Test
    @DisplayName("동일 평문도 암호화할 때마다 다른 IV 사용")
    void encryptUsesDifferentIvForSamePlaintext() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        byte[] first = service.encrypt("Bearer secret-token");
        byte[] second = service.encrypt("Bearer secret-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("Bearer secret-token");
        assertThat(service.decrypt(second)).isEqualTo("Bearer secret-token");
    }

    @Test
    @DisplayName("변조된 암호문 복호화 실패")
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
    @DisplayName("IV + 태그보다 짧은 입력 복호화 실패")
    void decryptRejectsInputShorterThanIvAndTag() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        assertThatThrownBy(() -> service.decrypt(new byte[27]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encrypted credential is invalid.");
    }

    @Test
    @DisplayName("잘못된 키 길이로 생성 실패")
    void constructorRejectsInvalidKeyLength() {
        String invalidKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new CredentialCryptoService(new CredentialCryptoProperties(invalidKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("security.credentials.key must decode to 32 bytes.");
    }

    @Test
    @DisplayName("빈 평문 암호화 실패")
    void encryptRejectsBlankPlaintext() {
        CredentialCryptoService service = new CredentialCryptoService(new CredentialCryptoProperties(KEY));

        assertThatThrownBy(() -> service.encrypt(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Credential plaintext must not be blank.");
    }
}
