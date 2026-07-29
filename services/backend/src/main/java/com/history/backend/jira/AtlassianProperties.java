package com.history.backend.jira;

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
        String apiGatewayUrl
) {
}
