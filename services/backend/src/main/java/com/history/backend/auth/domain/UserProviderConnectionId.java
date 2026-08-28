package com.history.backend.auth.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.IntegrationProviderConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProviderConnectionId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    @Convert(converter = IntegrationProviderConverter.class)
    private IntegrationProvider provider;

    public UserProviderConnectionId(UUID userId, IntegrationProvider provider) {
        this.userId = userId;
        this.provider = provider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserProviderConnectionId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && provider == that.provider;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, provider);
    }
}
