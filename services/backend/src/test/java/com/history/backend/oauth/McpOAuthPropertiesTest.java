package com.history.backend.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("McpOAuthProperties: issuer 정규화와 파생 URL")
class McpOAuthPropertiesTest {

    @Test
    @DisplayName("issuer 끝 슬래시는 제거되어 파생 URL에 이중 슬래시가 생기지 않는다")
    void stripsTrailingSlashesFromIssuer() {
        McpOAuthProperties properties = new McpOAuthProperties(
                "https://why-code.com/", "", Duration.ofHours(1), Duration.ofDays(30));

        assertThat(properties.issuer()).isEqualTo("https://why-code.com");
        assertThat(properties.resourceUrl()).isEqualTo("https://why-code.com/mcp");
        assertThat(properties.protectedResourceMetadataUrl())
                .isEqualTo("https://why-code.com/.well-known/oauth-protected-resource/mcp");
    }

    @Test
    @DisplayName("끝 슬래시가 여러 개여도 전부 제거된다")
    void stripsMultipleTrailingSlashes() {
        McpOAuthProperties properties = new McpOAuthProperties(
                "http://localhost:5173///", "", Duration.ofHours(1), Duration.ofDays(30));

        assertThat(properties.issuer()).isEqualTo("http://localhost:5173");
    }

    @Test
    @DisplayName("끝 슬래시가 없으면 그대로 둔다")
    void keepsIssuerWithoutTrailingSlash() {
        McpOAuthProperties properties = new McpOAuthProperties(
                "http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30));

        assertThat(properties.issuer()).isEqualTo("http://localhost:5173");
        assertThat(properties.resourceUrl()).isEqualTo("http://localhost:5173/mcp");
    }
}
