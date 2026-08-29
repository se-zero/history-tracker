package com.history.backend.github.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubInstallationMemberId implements Serializable {

    @Column(name = "installation_id", nullable = false)
    private UUID installationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public GitHubInstallationMemberId(UUID installationId, UUID userId) {
        this.installationId = installationId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GitHubInstallationMemberId that)) {
            return false;
        }
        return Objects.equals(installationId, that.installationId)
                && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(installationId, userId);
    }
}
