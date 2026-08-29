package com.history.backend.github.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// GitHub App 설치 접근권 공유 조인 테이블 — (installation, user) 복합키
@Getter
@Entity
@Table(name = "github_installation_users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubInstallationMember {

    @EmbeddedId
    private GitHubInstallationMemberId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public GitHubInstallationMember(GitHubInstallationMemberId id) {
        this.id = id;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
