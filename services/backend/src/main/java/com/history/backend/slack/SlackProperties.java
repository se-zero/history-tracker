package com.history.backend.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "slack")
public record SlackProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String userScopes,
        String authorizeUrl,
        String oauthAccessUrl
) {
}
