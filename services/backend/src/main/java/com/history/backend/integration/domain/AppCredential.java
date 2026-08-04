package com.history.backend.integration.domain;

import java.time.Instant;
import java.util.Arrays;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 프로젝트 무관 앱 수준 자격증명 1행 테이블 (provider가 자연키)
@Getter
@Entity
@Table(name = "app_credentials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppCredential {

    public static final String ATLASSIAN = "ATLASSIAN";

    @Id
    private String provider;

    @Column(name = "encrypted_credential", nullable = false)
    private byte[] encryptedCredential;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AppCredential atlassian(byte[] encryptedCredential) {
        return new AppCredential(ATLASSIAN, encryptedCredential);
    }

    private AppCredential(String provider, byte[] encryptedCredential) {
        this.provider = provider;
        // byte[] 가변성 차단을 위한 방어적 복사
        this.encryptedCredential = Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    public byte[] getEncryptedCredential() {
        // byte[] 가변성 차단을 위한 방어적 복사
        return Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    public void updateCredential(byte[] encryptedCredential) {
        this.encryptedCredential = Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    @PrePersist
    void prePersist() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
