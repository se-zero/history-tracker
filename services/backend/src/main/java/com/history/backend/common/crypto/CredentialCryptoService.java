package com.history.backend.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// 외부 연동 자격증명 AES-256-GCM 암호화/복호화
@Service
public class CredentialCryptoService {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final int MIN_ENCRYPTED_CREDENTIAL_BYTES = GCM_IV_BYTES + GCM_TAG_BYTES;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom;

    @Autowired
    public CredentialCryptoService(CredentialCryptoProperties properties) {
        this(properties, new SecureRandom());
    }

    CredentialCryptoService(CredentialCryptoProperties properties, SecureRandom secureRandom) {
        this.keySpec = new SecretKeySpec(decodeKey(properties.key()), KEY_ALGORITHM);
        this.secureRandom = secureRandom;
    }

    // 자격증명 평문 암호화
    public byte[] encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Credential plaintext must not be blank.");
        }

        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // 복호화 시 IV를 함께 읽을 수 있도록 IV + 암호문을 단일 바이트 배열로 직렬화
            return ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt credential.", exception);
        }
    }

    // 암호화된 자격증명 복호화
    public String decrypt(byte[] encryptedCredential) {
        if (encryptedCredential == null || encryptedCredential.length < MIN_ENCRYPTED_CREDENTIAL_BYTES) {
            throw new IllegalArgumentException("Encrypted credential is invalid.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(encryptedCredential);
        byte[] iv = new byte[GCM_IV_BYTES];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            // 키 불일치·데이터 변조로 인한 GCM 인증 실패는 서버 오류가 아닌 잘못된 입력으로 처리
            throw new IllegalArgumentException("Encrypted credential is invalid.", exception);
        }
    }

    // 암호화 키 설정값 검증 (base64 인코딩, 디코딩 후 32바이트)
    private static byte[] decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("security.credentials.key must be configured.");
        }

        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("security.credentials.key must be base64 encoded.", exception);
        }

        if (key.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("security.credentials.key must decode to 32 bytes.");
        }
        return key;
    }
}
