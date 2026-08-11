package com.history.backend.linear;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linear")
public record LinearProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String tokenUrl,
        String revokeUrl,
        // 만료 전 미리 갱신할 여유 시간. 로컬에서 크게 잡으면(예: PT23H) 갱신 경로를 즉시 검증할 수 있다.
        Duration refreshSkew
) {
}
