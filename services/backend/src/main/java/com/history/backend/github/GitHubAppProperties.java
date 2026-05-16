package com.history.backend.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github.app")
public record GitHubAppProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authorizeUrl,
        String accessTokenUrl,
        String userUrl,
        String installationsUrl
) {
}
