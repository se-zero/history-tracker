package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AsanaCredentialCodec: Asana OAuth 자격증명 JSON 암복호화")
class AsanaCredentialCodecTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("test-credential-key-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));

    private final CredentialCryptoService credentialCryptoService =
            new CredentialCryptoService(new CredentialCryptoProperties(KEY));
    private final AsanaCredentialCodec codec = new AsanaCredentialCodec(credentialCryptoService);

    @Test
    @DisplayName("암호화 후 복호화 시 access/refresh token·만료 시각 왕복")
    void encryptAndDecryptRoundTripsCredential() {
        AsanaCredential credential = new AsanaCredential(
                "access-token",
                "refresh-token",
                Instant.parse("2026-06-15T03:00:00Z")
        );

        byte[] encrypted = codec.encrypt(credential);
        AsanaCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(credential);
    }

    @Test
    @DisplayName("refresh_token이 없는(null) 자격증명도 왕복 — Asana 갱신 응답은 refresh_token을 회전하지 않는다")
    void encryptAndDecryptRoundTripsCredentialWithNullRefreshToken() {
        AsanaCredential credential = new AsanaCredential(
                "access-token",
                null,
                Instant.parse("2026-06-15T03:00:00Z")
        );

        byte[] encrypted = codec.encrypt(credential);
        AsanaCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(credential);
        assertThat(decrypted.refreshToken()).isNull();
    }

    @Test
    @DisplayName("깨진 JSON 복호화 시 IllegalStateException 발생")
    void decryptRejectsBrokenJson() {
        byte[] encrypted = credentialCryptoService.encrypt("not-valid-json");

        assertThatThrownBy(() -> codec.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
