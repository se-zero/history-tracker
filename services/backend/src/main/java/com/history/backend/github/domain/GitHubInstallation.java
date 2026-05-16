package com.history.backend.github.domain;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Entity
@Table(name = "github_installations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubInstallation {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "account_login", nullable = false)
    private String accountLogin;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installer_user_id", nullable = false)
    private User installerUser;

    @Column(name = "encrypted_installation_token")
    private byte[] encryptedInstallationToken;

    @Column(name = "installation_token_expires_at")
    private Instant installationTokenExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GitHubInstallation(Long installationId, String accountType, String accountLogin, User installerUser) {
        this.installationId = installationId;
        this.accountType = accountType;
        this.accountLogin = accountLogin;
        this.installerUser = installerUser;
    }

    public void updateAccount(String accountType, String accountLogin, User installerUser) {
        this.accountType = accountType;
        this.accountLogin = accountLogin;
        this.installerUser = installerUser;
    }

    public void updateInstallationToken(byte[] encryptedInstallationToken, Instant expiresAt) {
        this.encryptedInstallationToken = encryptedInstallationToken;
        this.installationTokenExpiresAt = expiresAt;
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
