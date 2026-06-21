package com.history.backend.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationProviderTest {

    @Test
    void displayNameReturnsHumanReadableProviderName() {
        assertThat(IntegrationProvider.GITHUB.displayName()).isEqualTo("GitHub");
        assertThat(IntegrationProvider.SLACK.displayName()).isEqualTo("Slack");
        assertThat(IntegrationProvider.JIRA.displayName()).isEqualTo("Jira");
    }

    // provider 추가 시 displayName 누락 방지 가드
    @Test
    void everyProviderHasDisplayName() {
        for (IntegrationProvider provider : IntegrationProvider.values()) {
            assertThat(provider.displayName()).isNotBlank();
        }
    }
}
