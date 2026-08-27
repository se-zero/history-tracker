package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.github.GitHubAppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GitHubAppJwtService: GitHub App JWT 생성")
class GitHubAppJwtServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("GitHub App 페이로드로 JWT 서명 생성")
    void createJwtSignsGitHubAppPayload() throws Exception {
        KeyPair keyPair = generateKeyPair();
        GitHubAppJwtService service = new GitHubAppJwtService(
                properties(privateKeyPem(keyPair)),
                OBJECT_MAPPER,
                Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC)
        );

        String jwt = service.createJwt();

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode header = decodeJson(parts[0]);
        JsonNode payload = decodeJson(parts[1]);
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("typ").asText()).isEqualTo("JWT");
        assertThat(payload.path("iss").asText()).isEqualTo("123456");
        assertThat(payload.path("iat").asLong()).isEqualTo(1_779_148_740L);
        assertThat(payload.path("exp").asLong()).isEqualTo(1_779_149_340L);
        assertThat(verifySignature(keyPair, parts)).isTrue();
    }

    @Test
    @DisplayName("PKCS#1 PEM 개인키로 JWT 생성 지원")
    void createJwtSupportsPkcs1PrivateKeyPem() throws Exception {
        KeyPair keyPair = generateKeyPair();
        GitHubAppJwtService service = new GitHubAppJwtService(
                properties(pkcs1PrivateKeyPem(keyPair)),
                OBJECT_MAPPER,
                Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC)
        );

        String jwt = service.createJwt();

        assertThat(verifySignature(keyPair, jwt.split("\\."))).isTrue();
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String privateKeyPem(KeyPair keyPair) {
        String key = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + key + "\n-----END PRIVATE KEY-----";
    }

    private String pkcs1PrivateKeyPem(KeyPair keyPair) {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) keyPair.getPrivate();
        byte[] pkcs1 = derSequence(
                derInteger(java.math.BigInteger.ZERO),
                derInteger(key.getModulus()),
                derInteger(key.getPublicExponent()),
                derInteger(key.getPrivateExponent()),
                derInteger(key.getPrimeP()),
                derInteger(key.getPrimeQ()),
                derInteger(key.getPrimeExponentP()),
                derInteger(key.getPrimeExponentQ()),
                derInteger(key.getCrtCoefficient())
        );
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(pkcs1);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + encoded + "\n-----END RSA PRIVATE KEY-----";
    }

    private JsonNode decodeJson(String value) throws Exception {
        return OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(value));
    }

    private boolean verifySignature(KeyPair keyPair, String[] parts) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(keyPair.getPublic().getEncoded())));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    private byte[] derSequence(byte[]... values) {
        return derValue(0x30, concat(values));
    }

    private byte[] derInteger(java.math.BigInteger value) {
        return derValue(0x02, value.toByteArray());
    }

    private byte[] derValue(int tag, byte[] value) {
        return concat(new byte[] {(byte) tag}, derLength(value.length), value);
    }

    private byte[] derLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        int bytesRequired = 0;
        int value = length;
        while (value > 0) {
            bytesRequired++;
            value >>= 8;
        }
        byte[] result = new byte[bytesRequired + 1];
        result[0] = (byte) (0x80 | bytesRequired);
        for (int index = bytesRequired; index > 0; index--) {
            result[index] = (byte) (length & 0xff);
            length >>= 8;
        }
        return result;
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

    private GitHubAppProperties properties(String privateKey) {
        return new GitHubAppProperties(
                "123456",
                "history-tracker",
                privateKey,
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "https://github.com/apps/history-tracker/installations/new",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user",
                "https://api.github.com/user/installations",
                "https://api.github.com/app/installations/{installation_id}/access_tokens",
                "https://api.github.com/installation/repositories",
                "https://api.github.com/repos/{owner}/{repo}/branches",
                "https://api.github.com/user/installations/{installation_id}/repositories"
        );
    }
}
