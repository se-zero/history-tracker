package com.history.backend.mcp.repository;

import com.history.backend.mcp.domain.McpWorkspaceBinding;
import com.history.backend.mcp.domain.McpWorkspaceBindingId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpWorkspaceBindingRepository extends JpaRepository<McpWorkspaceBinding, McpWorkspaceBindingId> {
}
