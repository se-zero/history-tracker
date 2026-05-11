package com.history.pipeline_worker.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class GitHubWebhookVerifier {

    private static final String SIGNATURE_PREFIX = "sha256=";
    private final String secret;

    public GitHubWebhookVerifier(@Value("${app.webhook.github.secret:}") String secret) {
        this.secret = secret;
    }

    public boolean verify(String payload, String signatureHeader) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        byte[] expected = hmacSha256(payload);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signatureHeader.substring(SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }

        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate GitHub webhook signature", e);
        }
    }
}
