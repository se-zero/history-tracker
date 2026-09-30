package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.history.backend.oauth.McpOAuthProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OAuthJwkProvider: MCP 인가 서버 서명키 로딩(PKCS#8 PEM 파싱·임시 키 생성)")
class OAuthJwkProviderTest {

    @Test
    @DisplayName("PKCS#8 PEM 개인키 파싱 → modulus 일치·private·kid는 thumbprint")
    void parsesPkcs8PemAndDerivesKidFromThumbprint() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        OAuthJwkProvider provider = new OAuthJwkProvider(properties(toPem(keyPair, false)));

        RSAKey rsaKey = provider.rsaKey();

        assertThat(rsaKey.isPrivate()).isTrue();
        assertThat(rsaKey.toRSAPublicKey().getModulus())
                .isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
        assertThat(rsaKey.getKeyID()).isEqualTo(rsaKey.computeThumbprint().toString());
    }

    @Test
    @DisplayName("개행을 리터럴 \\n으로 치환한 PEM도 같은 키로 파싱(env 한 줄 주입 지원)")
    void parsesPemWithLiteralNewlineEscapes() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        OAuthJwkProvider provider = new OAuthJwkProvider(properties(toPem(keyPair, true)));

        assertThat(provider.rsaKey().toRSAPublicKey().getModulus())
                .isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
    }

    @Test
    @DisplayName("privateKey가 빈 문자열이면 예외 없이 임시 키 생성")
    void generatesTemporaryKeyWhenPrivateKeyIsBlank() {
        OAuthJwkProvider provider = new OAuthJwkProvider(properties(""));

        assertThat(provider.rsaKey().isPrivate()).isTrue();
    }

    @Test
    @DisplayName("privateKey가 null이면 예외 없이 임시 키 생성")
    void generatesTemporaryKeyWhenPrivateKeyIsNull() {
        OAuthJwkProvider provider = new OAuthJwkProvider(properties(null));

        assertThat(provider.rsaKey().isPrivate()).isTrue();
    }

    @Test
    @DisplayName("PEM 형식이 아니면 IllegalStateException")
    void rejectsMalformedPrivateKey() {
        assertThatThrownBy(() -> new OAuthJwkProvider(properties("not-a-pem")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("jwkSource는 rsaKey 하나만 담은 JWKSource를 반환")
    void jwkSourceExposesTheSameRsaKey() throws Exception {
        OAuthJwkProvider provider = new OAuthJwkProvider(properties(toPem(generateRsaKeyPair(), false)));

        JWKSource<SecurityContext> jwkSource = provider.jwkSource();
        List<JWK> matches = jwkSource.get(new JWKSelector(new JWKMatcher.Builder().build()), null);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getKeyID()).isEqualTo(provider.rsaKey().getKeyID());
    }

    // ── 헬퍼 — 레포에 PEM 상수를 박지 않고 매 실행 시 런타임 생성(시크릿 스캐너 회피) ──

    private McpOAuthProperties properties(String privateKey) {
        return new McpOAuthProperties("http://localhost:5173", privateKey, Duration.ofHours(1), Duration.ofDays(30), "/oauth/consent");
    }

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String toPem(KeyPair keyPair, boolean literalNewlineEscapes) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        return literalNewlineEscapes ? pem.replace("\n", "\\n") : pem;
    }
}
