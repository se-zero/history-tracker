package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaddleSignatureVerifier: Paddle 결제 웹훅 서명 검증")
class PaddleSignatureVerifierTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-24T03:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final long NOW_EPOCH = FIXED_INSTANT.getEpochSecond();
    private static final Duration TOLERANCE = Duration.ofSeconds(5);
    private static final String SECRET = "test-paddle-webhook-secret";
    private static final String BODY =
            "{\"event_id\":\"evt_01\",\"event_type\":\"subscription.created\",\"occurred_at\":\"2026-09-24T03:00:00Z\"}";

    @Test
    @DisplayName("올바른 서명 → true")
    void verifyReturnsTrueForValidSignature() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH);
        String header = header(ts, computeHex(SECRET, ts, BODY));

        assertThat(verifier.verify(header, BODY)).isTrue();
    }

    @Test
    @DisplayName("서명값이 다르면 → false")
    void verifyReturnsFalseForWrongSignature() {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH);
        String header = header(ts, "deadbeef0000000000000000000000000000000000000000000000000000dead");

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("본문이 변조되면 → false (raw body가 서명 기준임을 고정)")
    void verifyReturnsFalseWhenBodyIsTampered() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH);
        String header = header(ts, computeHex(SECRET, ts, BODY));

        assertThat(verifier.verify(header, BODY + " ")).isFalse();
    }

    @Test
    @DisplayName("webhook secret이 빈 문자열이면 서명이 맞아도 → false (fail-closed, SlackSignatureVerifier와 같음)")
    void verifyReturnsFalseWhenSecretIsBlank() {
        PaddleSignatureVerifier verifier = verifier("");
        String ts = String.valueOf(NOW_EPOCH);
        String header = header(ts, "deadbeef0000000000000000000000000000000000000000000000000000dead");

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("ts가 6초 과거 → 허용 오차(5초)를 벗어나 false")
    void verifyReturnsFalseWhenTimestampIs6SecondsOld() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH - 6);
        String header = header(ts, computeHex(SECRET, ts, BODY));

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("ts가 5초 과거 → 허용 오차 경계값이라 true")
    void verifyReturnsTrueWhenTimestampIsExactly5SecondsOld() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH - 5);
        String header = header(ts, computeHex(SECRET, ts, BODY));

        assertThat(verifier.verify(header, BODY)).isTrue();
    }

    @Test
    @DisplayName("ts가 6초 미래 → 허용 오차를 벗어나 false")
    void verifyReturnsFalseWhenTimestampIs6SecondsInFuture() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH + 6);
        String header = header(ts, computeHex(SECRET, ts, BODY));

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("h1이 여러 개고 두 번째만 맞으면 → true (서명 키 교체 중 지원)")
    void verifyReturnsTrueWhenSecondH1Matches() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH);
        String correctHex = computeHex(SECRET, ts, BODY);
        String header = header(ts, "deadbeef0000000000000000000000000000000000000000000000000000dead", correctHex);

        assertThat(verifier.verify(header, BODY)).isTrue();
    }

    @Test
    @DisplayName("Paddle-Signature 헤더가 null → false")
    void verifyReturnsFalseWhenSignatureHeaderIsNull() {
        PaddleSignatureVerifier verifier = verifier(SECRET);

        assertThat(verifier.verify(null, BODY)).isFalse();
    }

    @Test
    @DisplayName("Paddle-Signature 헤더가 빈 문자열 → false")
    void verifyReturnsFalseWhenSignatureHeaderIsBlank() {
        PaddleSignatureVerifier verifier = verifier(SECRET);

        assertThat(verifier.verify("", BODY)).isFalse();
    }

    @Test
    @DisplayName("헤더에 ts가 없으면 → false")
    void verifyReturnsFalseWhenTimestampIsMissingFromHeader() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW_EPOCH);
        String header = "h1=" + computeHex(SECRET, ts, BODY);

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("헤더에 h1이 없으면 → false")
    void verifyReturnsFalseWhenH1IsMissingFromHeader() {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String header = "ts=" + NOW_EPOCH;

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("ts가 숫자가 아니면 → false")
    void verifyReturnsFalseWhenTimestampIsNotNumeric() throws Exception {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String header = "ts=not-a-number;h1=" + computeHex(SECRET, String.valueOf(NOW_EPOCH), BODY);

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    @Test
    @DisplayName("h1이 hex 형식이 아니면 → false")
    void verifyReturnsFalseWhenH1IsNotHex() {
        PaddleSignatureVerifier verifier = verifier(SECRET);
        String header = header(String.valueOf(NOW_EPOCH), "not-a-hex-value!!");

        assertThat(verifier.verify(header, BODY)).isFalse();
    }

    private PaddleSignatureVerifier verifier(String secret) {
        return new PaddleSignatureVerifier(secret, TOLERANCE, FIXED_CLOCK);
    }

    // Paddle-Signature 헤더 조립: ts=<ts>;h1=<hex>(;h1=<hex>...)
    private String header(String ts, String... hexValues) {
        StringBuilder builder = new StringBuilder("ts=").append(ts);
        for (String hex : hexValues) {
            builder.append(";h1=").append(hex);
        }
        return builder.toString();
    }

    // 구현과 독립적으로 expected 서명을 계산한다 — 같은 유틸을 공유하면 구현의 실수를 놓칠 수 있다
    private String computeHex(String secret, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }
}
