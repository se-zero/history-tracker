package com.history.backend.oauth.service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import com.history.backend.oauth.McpOAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// MCP 인가 서버 RSA 서명키 로딩 — env PKCS#8 PEM 파싱, 미설정 시 임시 키 생성
@Slf4j
@Component
public class OAuthJwkProvider {

    private final RSAKey rsaKey;

    public OAuthJwkProvider(McpOAuthProperties properties) {
        RSAPrivateCrtKey privateKey = loadPrivateKey(properties.privateKey());
        RSAPublicKey publicKey = derivePublicKey(privateKey);
        this.rsaKey = deriveKeyId(new RSAKey.Builder(publicKey).privateKey(privateKey).build());
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private RSAPrivateCrtKey loadPrivateKey(String privateKeyPem) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            // 다중 인스턴스·재기동에서 같은 키를 공유해야 하는데, 여기까지 오면 배포 환경에 키가
            // 없다는 뜻이다 — 기동은 막지 않되 이미 발급된 토큰이 전부 무효화됨을 알린다.
            log.warn("MCP_OAUTH_PRIVATE_KEY가 설정되지 않아 임시 RSA 키를 생성합니다. "
                    + "재기동하면 이전에 발급된 MCP 토큰이 전부 무효화됩니다.");
            return generateTemporaryPrivateKey();
        }
        return parsePkcs8Pem(privateKeyPem);
    }

    private RSAPrivateCrtKey generateTemporaryPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return (RSAPrivateCrtKey) keyPair.getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("임시 RSA 키 생성에 실패했습니다.", e);
        }
    }

    // env는 한 줄 문자열만 받으므로 개행이 리터럴 "\n"으로 이스케이프돼 들어올 수 있다 — 실제 개행으로 되돌린다.
    private RSAPrivateCrtKey parsePkcs8Pem(String pem) {
        try {
            String base64 = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateCrtKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("MCP_OAUTH_PRIVATE_KEY를 PKCS#8 PEM으로 해석할 수 없습니다.", e);
        }
    }

    // kid는 JWK thumbprint(RFC 7638)로 유도한다 — 키 회전 시에도 값이 재현 가능해 별도 저장이 필요 없다.
    private RSAKey deriveKeyId(RSAKey keyWithoutId) {
        try {
            return new RSAKey.Builder(keyWithoutId)
                    .keyID(keyWithoutId.computeThumbprint().toString())
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("RSA 키의 thumbprint를 계산할 수 없습니다.", e);
        }
    }

    private RSAPublicKey derivePublicKey(RSAPrivateCrtKey privateKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKeySpec publicKeySpec =
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
            return (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA 공개키를 유도할 수 없습니다.", e);
        }
    }
}
