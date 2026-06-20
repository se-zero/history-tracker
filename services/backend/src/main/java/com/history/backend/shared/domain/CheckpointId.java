package com.history.backend.shared.domain;

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
public class CheckpointId implements Serializable {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    @Convert(converter = IntegrationProviderConverter.class)
    private IntegrationProvider provider;

    @Column(name = "cursor_key", nullable = false)
    private String cursorKey;

    public CheckpointId(UUID projectId, IntegrationProvider provider, String cursorKey) {
        this.projectId = projectId;
        this.provider = provider;
        this.cursorKey = cursorKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CheckpointId that)) {
            return false;
        }
        return Objects.equals(projectId, that.projectId)
                && provider == that.provider
                && Objects.equals(cursorKey, that.cursorKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, provider, cursorKey);
    }
}
