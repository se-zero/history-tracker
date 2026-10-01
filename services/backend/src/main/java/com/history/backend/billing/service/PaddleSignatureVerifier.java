package com.history.backend.billing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.history.backend.billing.PaddleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// Paddle 결제 웹훅 서명 검증(Paddle-Signature: ts=<unix초>;h1=<hex>) — secret 미설정 시
// 모든 요청을 거부한다(fail-closed, SlackSignatureVerifier와 같은 이유).
@Component
public class PaddleSignatureVerifier {

    private final String webhookSecret;
    private final Duration tolerance;
    private final Clock clock;

    // Spring 빈 등록 생성자 — 두 생성자 중 이쪽이 Spring이 써야 할 쪽임을 명시한다
    @Autowired
    public PaddleSignatureVerifier(PaddleProperties properties) {
        this(properties.webhookSecret(), properties.signatureTolerance(), Clock.systemUTC());
    }

    // 테스트 전용 생성자 — Clock 주입으로 고정 시각 검증이 가능하다
    PaddleSignatureVerifier(String webhookSecret, Duration tolerance, Clock clock) {
        this.webhookSecret = webhookSecret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    // h1이 여러 개일 수 있다(키 교체 중) — 하나라도 맞으면 통과. 비교는 timing-safe.
    public boolean verify(String signatureHeader, String rawBody) {
        // secret이 비어 있으면 hmac 계산 전에 즉시 거부 — 미설정 환경에서 아무 요청이나 통과하는 것을 막는다
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        String timestampValue = null;
        List<String> signatureValues = new ArrayList<>();
        for (String part : signatureHeader.split(";")) {
            int separatorIndex = part.indexOf('=');
            if (separatorIndex < 0) {
                continue;
            }
            String key = part.substring(0, separatorIndex).trim();
            String value = part.substring(separatorIndex + 1).trim();
            if ("ts".equals(key)) {
                timestampValue = value;
            } else if ("h1".equals(key)) {
                signatureValues.add(value);
            }
        }
        if (timestampValue == null || signatureValues.isEmpty()) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampValue);
        } catch (NumberFormatException e) {
            return false;
        }
        // 리플레이·지연 공격 방지: 요청 타임스탬프가 허용 오차를 벗어나면 거부(과거·미래 양쪽)
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > tolerance.getSeconds()) {
            return false;
        }

        byte[] expected = hmacSha256(timestampValue + ":" + rawBody);
        for (String hex : signatureValues) {
            byte[] actual;
            try {
                actual = HexFormat.of().parseHex(hex);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // 길이가 다르면 상수 시간 비교가 의미 없으므로 먼저 확인한다
            if (expected.length == actual.length && MessageDigest.isEqual(expected, actual)) {
                return true;
            }
        }
        return false;
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate Paddle webhook signature", e);
        }
    }
}
