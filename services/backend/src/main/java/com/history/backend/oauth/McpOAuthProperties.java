package com.history.backend.oauth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// MCP 인가 서버(/oauth2/*)·리소스 서버(/mcp) 공통 설정값을 담는 클래스
@ConfigurationProperties(prefix = "mcp.oauth")
public record McpOAuthProperties(
        String issuer,
        String privateKey,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {

    public String resourceUrl() {
        return issuer + "/mcp";
    }

    public String protectedResourceMetadataUrl() {
        return issuer + "/.well-known/oauth-protected-resource/mcp";
    }
}
