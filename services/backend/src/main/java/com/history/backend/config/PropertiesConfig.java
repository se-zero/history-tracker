package com.history.backend.config;

import com.history.backend.common.crypto.CredentialCryptoProperties;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        CredentialCryptoProperties.class,
        JwtProperties.class,
        GitHubAppProperties.class
})
public class PropertiesConfig {
}
