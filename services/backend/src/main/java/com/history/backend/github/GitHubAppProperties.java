package com.history.backend.github;

import java.time.Duration;

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
        String repositoryBranchesUrl,
        String userInstallationRepositoriesUrl,
        String userInstallationUrl,
        String grantRevokeUrl,
        String appInstallationUrl,
        String organizationMembershipUrl,
        Duration refreshSkew
) {
}
