package com.history.backend.slack.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Slack 요청 서명 검증 — secret 미설정 시 모든 요청을 거부(fail-closed)
@Component
public class SlackSignatureVerifier {

    private static final String SIGNATURE_PREFIX = "v0=";
    private static final long REPLAY_WINDOW_SECONDS = 300;

    private final String signingSecret;
    private final Clock clock;

    // Spring 빈 등록 생성자 — 두 생성자 중 이쪽이 Spring이 써야 할 쪽임을 명시한다
    @Autowired
    public SlackSignatureVerifier(@Value("${slack.signing-secret:}") String signingSecret) {
        this(signingSecret, Clock.systemUTC());
    }

    // 테스트 전용 생성자 — Clock 주입으로 고정 시각 검증이 가능하다
    SlackSignatureVerifier(String signingSecret, Clock clock) {
        this.signingSecret = signingSecret;
        this.clock = clock;
    }

    // Slack Events API 서명 검증 (v0 scheme, HMAC-SHA256)
    public boolean verify(String timestampHeader, String signatureHeader, String rawBody) {
        // secret이 비어 있으면 hmac 계산 전에 즉시 거부 — 미설정 환경에서 아무 요청이나 통과하는 것을 막는다
        if (signingSecret == null || signingSecret.isBlank()) {
            return false;
        }
        if (timestampHeader == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        // 리플레이 공격 방지: 요청 타임스탬프가 현재 시각의 ±300초를 벗어나면 거부
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > REPLAY_WINDOW_SECONDS) {
            return false;
        }
        String basestring = "v0:" + timestampHeader + ":" + rawBody;
        byte[] expected = hmacSha256(basestring);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signatureHeader.substring(SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // 길이가 다르면 상수 시간 비교가 의미 없으므로 먼저 확인한다
        if (expected.length != actual.length) {
            return false;
        }
        // 타이밍 공격 방지: 서명 값에 따라 분기하지 않는 상수 시간 비교
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate Slack request signature", e);
        }
    }
}
