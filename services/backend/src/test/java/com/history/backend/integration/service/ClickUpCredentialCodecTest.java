package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClickUpCredentialCodec: ClickUp OAuth 자격증명 JSON 직렬화·복호화")
class ClickUpCredentialCodecTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("test-credential-key-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));

    private final CredentialCryptoService credentialCryptoService =
            new CredentialCryptoService(new CredentialCryptoProperties(KEY));
    private final ClickUpCredentialCodec codec = new ClickUpCredentialCodec(credentialCryptoService);

    @Test
    @DisplayName("직렬화 후 암호화·복호화 시 access token 왕복 (ClickUp 토큰은 만료·회전이 없어 access_token만 담는다)")
    void serializeAndDecryptRoundTripsAccessToken() {
        ClickUpCredential credential = new ClickUpCredential("access-token");

        byte[] encrypted = credentialCryptoService.encrypt(codec.serialize(credential));
        ClickUpCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(credential);
    }

    @Test
    @DisplayName("깨진 JSON 복호화 시 IllegalStateException 발생")
    void decryptRejectsBrokenJson() {
        byte[] encrypted = credentialCryptoService.encrypt("not-valid-json");

        assertThatThrownBy(() -> codec.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
