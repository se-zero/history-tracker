package com.history.backend.clickup;

import org.springframework.boot.context.properties.ConfigurationProperties;

// ClickUp access token은 만료·회전이 없어 Asana/Linear와 달리 refreshSkew·revokeUrl이 없다.
@ConfigurationProperties(prefix = "clickup")
public record ClickUpProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String tokenUrl
) {
}
