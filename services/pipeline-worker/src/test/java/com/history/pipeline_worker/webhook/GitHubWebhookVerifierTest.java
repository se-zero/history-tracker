package com.history.pipeline_worker.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookVerifierTest {

    @Test
    void verify_validSignature_returnsTrue() throws Exception {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier("secret");
        String payload = "{\"action\":\"closed\"}";
        String signature = "sha256=" + hmac("secret", payload);

        assertThat(verifier.verify(payload, signature)).isTrue();
    }

    @Test
    void verify_invalidSignature_returnsFalse() {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier("secret");

        assertThat(verifier.verify("{}", "sha256=abcdef")).isFalse();
    }

    @Test
    void verify_blankSecret_returnsFalse() {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier("");

        assertThat(verifier.verify("{}", "sha256=abcdef")).isFalse();
    }

    private String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
