package com.history.backend.jira;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlassian")
public record AtlassianProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String scopes,
        String authorizeUrl,
        String tokenUrl,
        String accessibleResourcesUrl,
        String apiGatewayUrl,
        // 만료 전 미리 갱신할 여유 시간. 로컬에서 크게 잡으면(예: PT2H) 갱신 경로를 즉시 검증할 수 있다.
        Duration refreshSkew
) {
}
