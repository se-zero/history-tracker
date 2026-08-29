package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.common.crypto.CredentialCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlackCredentialCodec: Slack 자격증명 JSON 직렬화·복호화·평문 폴백")
class SlackCredentialCodecTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("test-credential-key-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));

    private final CredentialCryptoService credentialCryptoService =
            new CredentialCryptoService(new CredentialCryptoProperties(KEY));
    private final SlackCredentialCodec codec = new SlackCredentialCodec(credentialCryptoService);

    @Test
    @DisplayName("user_token + bot_token 직렬화 후 암호화·복호화 왕복")
    void serializeAndEncryptRoundTripsBothTokens() {
        SlackCredential credential = new SlackCredential("xoxp-user", "xoxb-bot");

        byte[] encrypted = codec.encrypt(credential);
        SlackCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted.userToken()).isEqualTo("xoxp-user");
        assertThat(decrypted.botToken()).isEqualTo("xoxb-bot");
    }

    @Test
    @DisplayName("bot_token이 null인 자격증명도 직렬화·복호화 왕복이 된다")
    void serializeAndEncryptRoundTripsWhenBotTokenIsNull() {
        SlackCredential credential = new SlackCredential("xoxp-user", null);

        byte[] encrypted = codec.encrypt(credential);
        SlackCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted.userToken()).isEqualTo("xoxp-user");
        assertThat(decrypted.botToken()).isNull();
    }

    @Test
    @DisplayName("serialize 결과에 JSON 키 user_token, bot_token이 담긴다 (worker가 읽는 키 이름)")
    void serializeUsesWorkerKeyNames() {
        SlackCredential credential = new SlackCredential("xoxp-user", "xoxb-bot");

        String json = codec.serialize(credential);

        assertThat(json).contains("\"user_token\"");
        assertThat(json).contains("\"bot_token\"");
        assertThat(json).doesNotContain("\"access_token\"");
    }

    @Test
    @DisplayName("평문(레거시) 복호화 시 전체 문자열을 userToken으로, botToken은 null로 폴백한다")
    void decryptFallsBackToPlainTextLegacyToken() {
        byte[] encrypted = credentialCryptoService.encrypt("xoxp-legacy-token");

        SlackCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted.userToken()).isEqualTo("xoxp-legacy-token");
        assertThat(decrypted.botToken()).isNull();
    }

    @Test
    @DisplayName("JSON 파싱 자체가 실패하면(깨진 JSON) 전체 평문을 userToken으로 폴백한다 — IllegalStateException을 던지면 이 테스트가 실패해야 한다")
    void decryptFallsBackWhenJsonIsMalformed() {
        byte[] encrypted = credentialCryptoService.encrypt("{not valid json}");

        SlackCredential decrypted = codec.decrypt(encrypted);

        assertThat(decrypted.userToken()).isEqualTo("{not valid json}");
        assertThat(decrypted.botToken()).isNull();
    }

    @Test
    @DisplayName("JSON object인데 user_token 키가 없으면 IllegalStateException 발생")
    void decryptThrowsWhenUserTokenMissingFromJsonObject() {
        byte[] encrypted = credentialCryptoService.encrypt("{\"bot_token\":\"xoxb-bot\"}");

        assertThatThrownBy(() -> codec.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack credential field: user_token");
    }

    @Test
    @DisplayName("JSON object인데 user_token 값이 blank이면 IllegalStateException 발생")
    void decryptThrowsWhenUserTokenBlankInJsonObject() {
        byte[] encrypted = credentialCryptoService.encrypt("{\"user_token\":\"\",\"bot_token\":\"xoxb-bot\"}");

        assertThatThrownBy(() -> codec.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack credential field: user_token");
    }

    @Test
    @DisplayName("JSON object인데 user_token 값이 null이면 IllegalStateException 발생")
    void decryptThrowsWhenUserTokenNullInJsonObject() {
        byte[] encrypted = credentialCryptoService.encrypt("{\"user_token\":null,\"bot_token\":\"xoxb-bot\"}");

        assertThatThrownBy(() -> codec.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack credential field: user_token");
    }
}
