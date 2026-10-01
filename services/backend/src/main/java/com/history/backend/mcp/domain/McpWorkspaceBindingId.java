package com.history.backend.mcp.domain;

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
public class McpWorkspaceBindingId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "workspace_path", nullable = false)
    private String workspacePath;

    public McpWorkspaceBindingId(UUID userId, String workspacePath) {
        this.userId = userId;
        this.workspacePath = workspacePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpWorkspaceBindingId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(workspacePath, that.workspacePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, workspacePath);
    }
}
