package com.history.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = new ObjectMapper();
    }

    // JWT access token 발급
    public String issueAccessToken(UUID userId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(jwtProperties.accessTokenTtl());
        return issueToken(userId, ACCESS_TOKEN_TYPE, issuedAt, expiresAt);
    }

    // access token 검증 및 인증 사용자 반환
    public AuthenticatedUser validateAccessToken(String token) {
        JwtClaims claims = parseAndValidate(token);
        if (!ACCESS_TOKEN_TYPE.equals(claims.tokenType())) {
            throw new JwtAuthenticationException("Unsupported token type.");
        }
        if (!claims.expiresAt().isAfter(Instant.now())) {
            throw new JwtAuthenticationException("Expired access token.");
        }
        return new AuthenticatedUser(claims.subject());
    }

    private String issueToken(UUID userId, String tokenType, Instant issuedAt, Instant expiresAt) {
        String header = encodeJson(Map.of(
                "alg", "HS256",
                "typ", "JWT"
        ));
        String payload = encodeJson(Map.of(
                "sub", userId.toString(),
                "type", tokenType,
                "iat", issuedAt.getEpochSecond(),
                "exp", expiresAt.getEpochSecond()
        ));
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput);
    }

    private JwtClaims parseAndValidate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtAuthenticationException("Malformed JWT.");
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = BASE64_URL_DECODER.decode(sign(signingInput));
        byte[] actualSignature = decodePart(parts[2]);

        // 타이밍 공격 방지를 위한 상수 시간 비교
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new JwtAuthenticationException("Invalid JWT signature.");
        }

        Map<String, Object> claims = readJsonPart(parts[1]);
        return new JwtClaims(
                UUID.fromString(requiredString(claims, "sub")),
                requiredString(claims, "type"),
                Instant.ofEpochSecond(requiredLong(claims, "iat")),
                Instant.ofEpochSecond(requiredLong(claims, "exp"))
        );
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize JWT.", exception);
        }
    }

    private Map<String, Object> readJsonPart(String encodedPart) {
        try {
            return objectMapper.readValue(decodePart(encodedPart), CLAIMS_TYPE);
        } catch (Exception exception) {
            throw new JwtAuthenticationException("Invalid JWT payload.");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign JWT.", exception);
        }
    }

    private byte[] decodePart(String value) {
        try {
            return BASE64_URL_DECODER.decode(value);
        } catch (IllegalArgumentException exception) {
            throw new JwtAuthenticationException("Invalid JWT encoding.");
        }
    }

    private String requiredString(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new JwtAuthenticationException("Missing JWT claim: " + key);
    }

    private long requiredLong(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        throw new JwtAuthenticationException("Missing JWT claim: " + key);
    }
}
