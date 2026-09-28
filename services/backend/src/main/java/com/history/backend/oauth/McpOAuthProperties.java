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

    // 끝 슬래시가 붙은 issuer를 그대로 이어 붙이면 aud·메타데이터 URL이 "…//mcp"가 되어 기동은 되지만
    // 클라이언트의 resource 값과 조용히 어긋난다 — 설정 단계에서 잘라 둔다.
    public McpOAuthProperties {
        if (issuer != null) {
            issuer = issuer.replaceAll("/+$", "");
        }
    }

    public String resourceUrl() {
        return issuer + "/mcp";
    }

    public String protectedResourceMetadataUrl() {
        return issuer + "/.well-known/oauth-protected-resource/mcp";
    }
}
