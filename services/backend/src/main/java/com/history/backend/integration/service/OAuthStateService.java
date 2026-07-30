package com.history.backend.integration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.security.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// OAuth 콜백 state 토큰 발급·검증. JWT가 없는 콜백 요청에서 신원·프로젝트 소유권을 증명하는 유일한 수단이라
// typ 클레임으로 access token과 용도를 분리하되, 서명 키는 JwtProperties.secret()을 재사용한다
// (JwtTokenService 자체는 클레임이 sub/type/iat/exp로 고정돼 있어 인증 코어를 건드리지 않는 쪽을 택했다).
@Service
public class OAuthStateService {

    private static final String STATE_TYPE = "oauth_state";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public OAuthStateService(JwtProperties jwtProperties) {
        this(jwtProperties, Clock.systemUTC());
    }

    OAuthStateService(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
    }

    // 프로젝트·사용자·provider를 서명해 담은 state 토큰 발급 (TTL 10분)
    public String issue(UUID projectId, UUID userId, String provider) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(STATE_TTL);
        String payload = encodeJson(Map.of(
                "typ", STATE_TYPE,
                "pid", projectId.toString(),
                "uid", userId.toString(),
                "prv", provider,
                "nonce", UUID.randomUUID().toString(),
                "iat", issuedAt.getEpochSecond(),
                "exp", expiresAt.getEpochSecond()
        ));
        return payload + "." + sign(payload);
    }

    // state 검증 후 클레임 복원. 발급 시점과 다른 provider면 거부한다.
    public OAuthStateClaims verify(String state, String expectedProvider) {
        String[] parts = state == null ? new String[0] : state.split("\\.");
        if (parts.length != 2) {
            throw new OAuthStateException("Malformed OAuth state.");
        }

        String payload = parts[0];
        byte[] expectedSignature = BASE64_URL_DECODER.decode(sign(payload));
        byte[] actualSignature = decodePart(parts[1]);

        // 타이밍 공격 방지를 위한 상수 시간 비교
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new OAuthStateException("Invalid OAuth state signature.");
        }

        Map<String, Object> claims = readJsonPart(payload);
        if (!STATE_TYPE.equals(claims.get("typ"))) {
            throw new OAuthStateException("Unsupported OAuth state type.");
        }
        String provider = requiredString(claims, "prv");
        if (!provider.equals(expectedProvider)) {
            throw new OAuthStateException("OAuth state provider mismatch.");
        }
        if (!Instant.ofEpochSecond(requiredLong(claims, "exp")).isAfter(Instant.now(clock))) {
            throw new OAuthStateException("Expired OAuth state.");
        }

        return new OAuthStateClaims(
                UUID.fromString(requiredString(claims, "pid")),
                UUID.fromString(requiredString(claims, "uid")),
                provider
        );
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize OAuth state.", exception);
        }
    }

    private Map<String, Object> readJsonPart(String encodedPart) {
        try {
            return objectMapper.readValue(decodePart(encodedPart), CLAIMS_TYPE);
        } catch (Exception exception) {
            throw new OAuthStateException("Invalid OAuth state payload.");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign OAuth state.", exception);
        }
    }

    private byte[] decodePart(String value) {
        try {
            return BASE64_URL_DECODER.decode(value);
        } catch (IllegalArgumentException exception) {
            throw new OAuthStateException("Invalid OAuth state encoding.");
        }
    }

    private String requiredString(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new OAuthStateException("Missing OAuth state claim: " + key);
    }

    private long requiredLong(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        throw new OAuthStateException("Missing OAuth state claim: " + key);
    }
}
