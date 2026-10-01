package com.history.backend.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

// MCP 질의의 사용자별 분당 호출 상한
@ConfigurationProperties(prefix = "mcp.rate-limit")
public record McpRateLimitProperties(int perMinute) {
}
