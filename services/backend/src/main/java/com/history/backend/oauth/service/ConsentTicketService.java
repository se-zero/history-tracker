package com.history.backend.oauth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.security.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// SPA 허용 화면에서 받은 사용자의 동의를 /oauth2/authorize 요청으로 넘기는 1회용 티켓.
// 서명 구조는 OAuthStateService와 같고, typ 클레임과 쿼리 해시(qh)로 용도·요청을 묶는다.
@Slf4j
@Service
public class ConsentTicketService {

    public static final Duration TTL = Duration.ofSeconds(60);

    private static final String TICKET_TYPE = "oauth_consent";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 소비된 nonce → 티켓 만료 시각. 인스턴스 메모리에만 둔다: 티켓 수명이 60초이고 HttpOnly 쿠키로만 오가며
    // 쿼리 해시에 묶여 있어, 재시작·다중 인스턴스로 이 집합이 비어도 노릴 수 있는 창이 그 60초뿐이다.
    private final ConcurrentHashMap<String, Instant> consumedNonces = new ConcurrentHashMap<>();

    @Autowired
    public ConsentTicketService(JwtProperties jwtProperties) {
        this(jwtProperties, Clock.systemUTC());
    }

    ConsentTicketService(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
    }

    // 사용자·authorize 쿼리 해시를 서명해 담은 티켓 발급 (TTL 60초)
    public String issue(UUID userId, String rawQuery) {
        Instant issuedAt = Instant.now(clock);
        try {
            String payload = BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(Map.of(
                    "typ", TICKET_TYPE,
                    "uid", userId.toString(),
                    "qh", queryHash(rawQuery),
                    "nonce", UUID.randomUUID().toString(),
                    "iat", issuedAt.getEpochSecond(),
                    "exp", issuedAt.plus(TTL).getEpochSecond()
            )));
            return payload + "." + sign(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to issue consent ticket.", exception);
        }
    }

    // 검증에 성공한 티켓만 nonce를 소모한다 — 실패 요청이 정상 티켓을 태워 버리지 못하게 하기 위함이다.
    public Optional<UUID> consume(String ticket, String rawQuery) {
        try {
            String[] parts = ticket == null ? new String[0] : ticket.split("\\.");
            if (parts.length != 2) {
                return reject("malformed");
            }

            byte[] expectedSignature = BASE64_URL_DECODER.decode(sign(parts[0]));
            byte[] actualSignature = BASE64_URL_DECODER.decode(parts[1]);
            // 타이밍 공격 방지를 위한 상수 시간 비교
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                return reject("signature");
            }

            Map<String, Object> claims = objectMapper.readValue(BASE64_URL_DECODER.decode(parts[0]), CLAIMS_TYPE);
            if (!TICKET_TYPE.equals(claims.get("typ"))) {
                return reject("type");
            }
            Instant expiresAt = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
            Instant now = Instant.now(clock);
            if (!expiresAt.isAfter(now)) {
                return reject("expired");
            }
            byte[] expectedHash = queryHash(rawQuery).getBytes(StandardCharsets.UTF_8);
            byte[] actualHash = ((String) claims.get("qh")).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                return reject("query_mismatch");
            }

            UUID userId = UUID.fromString((String) claims.get("uid"));
            String nonce = (String) claims.get("nonce");
            consumedNonces.values().removeIf(expiry -> !expiry.isAfter(now));
            if (nonce == null) {
                return reject("malformed");
            }
            if (consumedNonces.putIfAbsent(nonce, expiresAt) != null) {
                return reject("reused");
            }
            return Optional.of(userId);
        } catch (Exception exception) {
            // 예외 메시지에는 디코딩하던 입력 조각이 들어갈 수 있어 사유 코드만 남긴다.
            return reject("malformed");
        }
    }

    // 실기동에서 "허용을 눌러도 계속 허용 화면"의 원인을 구분하기 위한 사유 코드 로그 — 티켓·쿼리·사용자 id는 찍지 않는다.
    private Optional<UUID> reject(String reason) {
        log.info("Consent ticket rejected. reason={}", reason);
        return Optional.empty();
    }

    private String queryHash(String rawQuery) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((rawQuery == null ? "" : rawQuery).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash authorize query.", exception);
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign consent ticket.", exception);
        }
    }
}
