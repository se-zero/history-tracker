package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlackSignatureVerifier: Slack 요청 서명 검증")
class SlackSignatureVerifierTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-29T03:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final long NOW_EPOCH = FIXED_INSTANT.getEpochSecond();
    private static final String SECRET = "test-signing-secret";
    private static final String BODY = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";

    @Test
    @DisplayName("올바른 서명 → true")
    void verifyReturnsTrueForValidSignature() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String timestamp = String.valueOf(NOW_EPOCH);
        String signature = computeSignature(SECRET, timestamp, BODY);

        assertThat(verifier.verify(timestamp, signature, BODY)).isTrue();
    }

    @Test
    @DisplayName("서명값이 다르면 → false")
    void verifyReturnsFalseForInvalidSignature() {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String timestamp = String.valueOf(NOW_EPOCH);

        assertThat(verifier.verify(timestamp,
                "v0=deadbeef0000000000000000000000000000000000000000000000000000dead", BODY)).isFalse();
    }

    @Test
    @DisplayName("signing secret이 빈 문자열이면 서명이 맞아도 → false (fail-closed, GitHubWebhookVerifier와 같음)")
    void verifyReturnsFalseWhenSecretIsBlank() {
        // Java는 빈 키로 HMAC SecretKeySpec을 만들 수 없다. fail-closed는 hmac 계산 전에
        // 거부해야 하므로, 어떤 서명 문자열이든 false여야 한다.
        SlackSignatureVerifier verifier = verifier("");

        assertThat(verifier.verify(String.valueOf(NOW_EPOCH),
                "v0=deadbeef0000000000000000000000000000000000000000000000000000dead", BODY)).isFalse();
    }

    @Test
    @DisplayName("timestamp가 301초 과거 → 리플레이 방지로 false")
    void verifyReturnsFalseWhenTimestampIs301SecondsOld() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String timestamp = String.valueOf(NOW_EPOCH - 301);
        String signature = computeSignature(SECRET, timestamp, BODY);

        assertThat(verifier.verify(timestamp, signature, BODY)).isFalse();
    }

    @Test
    @DisplayName("timestamp가 정확히 300초 차이 → 허용 (창 경계값 포함)")
    void verifyReturnsTrueWhenTimestampIsExactly300SecondsOld() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String timestamp = String.valueOf(NOW_EPOCH - 300);
        String signature = computeSignature(SECRET, timestamp, BODY);

        assertThat(verifier.verify(timestamp, signature, BODY)).isTrue();
    }

    @Test
    @DisplayName("timestamp 헤더가 null → false")
    void verifyReturnsFalseWhenTimestampIsNull() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String signature = computeSignature(SECRET, String.valueOf(NOW_EPOCH), BODY);

        assertThat(verifier.verify(null, signature, BODY)).isFalse();
    }

    @Test
    @DisplayName("timestamp 헤더가 숫자가 아님 → false")
    void verifyReturnsFalseWhenTimestampIsNotNumeric() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String signature = computeSignature(SECRET, String.valueOf(NOW_EPOCH), BODY);

        assertThat(verifier.verify("not-a-number", signature, BODY)).isFalse();
    }

    @Test
    @DisplayName("signature 헤더가 null → false")
    void verifyReturnsFalseWhenSignatureIsNull() {
        SlackSignatureVerifier verifier = verifier(SECRET);

        assertThat(verifier.verify(String.valueOf(NOW_EPOCH), null, BODY)).isFalse();
    }

    @Test
    @DisplayName("signature가 v0= 으로 시작하지 않으면 (sha256=...) → false")
    void verifyReturnsFalseWhenSignatureDoesNotStartWithV0Prefix() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String timestamp = String.valueOf(NOW_EPOCH);
        // 올바른 HMAC이지만 잘못된 prefix
        String wrongPrefix = "sha256=" + hmacHex(SECRET, "v0:" + timestamp + ":" + BODY);

        assertThat(verifier.verify(timestamp, wrongPrefix, BODY)).isFalse();
    }

    @Test
    @DisplayName("본문이 한 글자라도 다르면 → false (raw body가 서명 기준임을 고정)")
    void verifyReturnsFalseWhenBodyDiffers() throws Exception {
        SlackSignatureVerifier verifier = verifier(SECRET);
        String timestamp = String.valueOf(NOW_EPOCH);
        String signature = computeSignature(SECRET, timestamp, BODY);

        assertThat(verifier.verify(timestamp, signature, BODY + " ")).isFalse();
    }

    private SlackSignatureVerifier verifier(String secret) {
        return new SlackSignatureVerifier(secret, FIXED_CLOCK);
    }

    // 구현과 독립적으로 expected 서명을 계산한다 — 같은 유틸을 공유하면 구현의 실수를 놓칠 수 있다
    private String computeSignature(String secret, String timestamp, String body) throws Exception {
        return "v0=" + hmacHex(secret, "v0:" + timestamp + ":" + body);
    }

    private String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
