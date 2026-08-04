package com.history.backend.config;

import com.history.backend.auth.UserPurgeProperties;
import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.jira.AtlassianPersonalDataReportProperties;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.security.JwtProperties;
import com.history.backend.slack.SlackProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        CredentialCryptoProperties.class,
        JwtProperties.class,
        GitHubAppProperties.class,
        SlackProperties.class,
        AtlassianProperties.class,
        AtlassianPersonalDataReportProperties.class,
        UserPurgeProperties.class
})
public class PropertiesConfig {
}
