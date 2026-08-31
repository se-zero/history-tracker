package com.history.backend.github.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자 GitHub OAuth 토큰 1행 — PK가 users.id와 같다. 생성하지 않는 이유는 사용자당 자격증명이
// 하나뿐이라 별도 surrogate key가 없고, 사용자 파기 시 FK CASCADE로 함께 지워져야 하기 때문이다.
@Getter
@Entity
@Table(name = "github_user_credentials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubUserCredentialEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "encrypted_credential", nullable = false)
    private byte[] encryptedCredential;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GitHubUserCredentialEntity(UUID userId, byte[] encryptedCredential) {
        this.userId = userId;
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
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
