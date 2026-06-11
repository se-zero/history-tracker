package com.history.backend.shared.domain;

import java.time.Instant;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.project.domain.Project;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// pipeline-worker 수집 진행 커서 — (project, provider, cursor_key) 복합키 공유 테이블
@Getter
@Entity
@Table(name = "checkpoints")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checkpoint {

    @EmbeddedId
    private CheckpointId id;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "cursor_value", nullable = false)
    private Instant cursorValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Checkpoint(
            Project project,
            IntegrationProvider provider,
            String cursorKey,
            Instant cursorValue
    ) {
        this.project = project;
        this.id = new CheckpointId(project.getId(), provider, cursorKey);
        this.cursorValue = cursorValue;
    }

    public IntegrationProvider getProvider() {
        return id.getProvider();
    }

    public String getCursorKey() {
        return id.getCursorKey();
    }

    public void updateCursorValue(Instant cursorValue) {
        this.cursorValue = cursorValue;
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
