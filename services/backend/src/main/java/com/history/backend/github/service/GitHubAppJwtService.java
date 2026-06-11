package com.history.backend.github.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.github.GitHubAppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// GitHub App API 인증용 JWT(RS256) 서명 생성
@Component
public class GitHubAppJwtService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final GitHubAppProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public GitHubAppJwtService(GitHubAppProperties properties) {
        this(properties, new ObjectMapper(), Clock.systemUTC());
    }

    GitHubAppJwtService(GitHubAppProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String createJwt() {
        if (properties.appId() == null || properties.appId().isBlank()) {
            throw new IllegalStateException("GitHub App ID is not configured.");
        }
        if (properties.privateKey() == null || properties.privateKey().isBlank()) {
            throw new IllegalStateException("GitHub App private key is not configured.");
        }

        Instant now = Instant.now(clock);
        String header = base64Json(new JwtHeader("RS256", "JWT"));
        // clock skew 대비 iat는 60초 과거로, exp는 GitHub 최대 허용(10분) 내인 9분 후로 설정
        String payload = base64Json(new JwtPayload(
                now.minusSeconds(60).getEpochSecond(),
                now.plusSeconds(540).getEpochSecond(),
                properties.appId()
        ));
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput);
    }

    private String base64Json(Object value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize GitHub App JWT.", exception);
        }
    }

    private String sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(parsePrivateKey(properties.privateKey()));
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return BASE64_URL_ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to sign GitHub App JWT.", exception);
        }
    }

    // PEM private key 파싱 (PKCS#8·PKCS#1 RSA 형식 지원)
    private PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        // 환경변수로 한 줄 주입된 PEM의 이스케이프된 개행 복원
        String normalizedPem = pem.replace("\\n", "\n");
        String keyBody = normalizedPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyBody);
        if (normalizedPem.contains("BEGIN RSA PRIVATE KEY")) {
            keyBytes = wrapPkcs1RsaPrivateKey(keyBytes);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    // PKCS#1 RSA 키의 PKCS#8 DER 구조 래핑 (KeyFactory가 PKCS#8만 지원)
    private byte[] wrapPkcs1RsaPrivateKey(byte[] pkcs1) {
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] algorithm = new byte[] {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] privateKey = concat(new byte[] {0x04}, encodeLength(pkcs1.length), pkcs1);
        return concat(new byte[] {0x30}, encodeLength(version.length + algorithm.length + privateKey.length),
                version, algorithm, privateKey);
    }

    // DER 가변 길이 인코딩 (128 미만 1바이트, 이상은 길이의 길이 + 빅엔디안)
    private byte[] encodeLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        int bytesRequired = 0;
        int value = length;
        while (value > 0) {
            bytesRequired++;
            value >>= 8;
        }
        byte[] encoded = new byte[bytesRequired + 1];
        encoded[0] = (byte) (0x80 | bytesRequired);
        for (int i = bytesRequired; i > 0; i--) {
            encoded[i] = (byte) (length & 0xff);
            length >>= 8;
        }
        return encoded;
    }

    private byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private record JwtHeader(String alg, String typ) {
    }

    private record JwtPayload(long iat, long exp, String iss) {
    }
}
