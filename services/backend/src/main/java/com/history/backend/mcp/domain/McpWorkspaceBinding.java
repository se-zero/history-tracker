package com.history.backend.mcp.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * MCP 클라이언트의 작업 폴더가 어느 프로젝트에 연결됐는지 저장한다. 다른 기능의 엔티티에 결합하지 않으려고
 * 연관관계 없이 id만 갖는다(FK·CASCADE는 DB에만 있고, 소유 검증은 매번 ProjectService로 한다).
 */
@Getter
@Entity
@Table(name = "mcp_workspace_bindings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpWorkspaceBinding {

    @EmbeddedId
    private McpWorkspaceBindingId id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public McpWorkspaceBinding(UUID userId, String workspacePath, UUID projectId) {
        this.id = new McpWorkspaceBindingId(userId, workspacePath);
        this.projectId = projectId;
    }

    public void changeProject(UUID projectId) {
        this.projectId = projectId;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
