package com.history.backend.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github.app")
public record GitHubAppProperties(
        String appId,
        String appSlug,
        String privateKey,
        String clientId,
        String clientSecret,
        String redirectUri,
        String installationUrl,
        String authorizeUrl,
        String accessTokenUrl,
        String userUrl,
        String installationsUrl,
        String installationAccessTokenUrl,
        String installationRepositoriesUrl
) {
}
