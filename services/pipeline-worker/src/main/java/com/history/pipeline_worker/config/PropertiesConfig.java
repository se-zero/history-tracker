package com.history.pipeline_worker.config;

import com.history.pipeline_worker.common.crypto.CredentialCryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        CredentialCryptoProperties.class
})
public class PropertiesConfig {
}
