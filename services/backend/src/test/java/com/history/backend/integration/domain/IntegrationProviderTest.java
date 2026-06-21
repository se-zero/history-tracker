package com.history.backend.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IntegrationProvider: provider displayName 검증")
class IntegrationProviderTest {

    @Test
    @DisplayName("각 provider의 displayName 반환 검증")
    void displayNameReturnsHumanReadableProviderName() {
        assertThat(IntegrationProvider.GITHUB.displayName()).isEqualTo("GitHub");
        assertThat(IntegrationProvider.SLACK.displayName()).isEqualTo("Slack");
        assertThat(IntegrationProvider.JIRA.displayName()).isEqualTo("Jira");
    }

    // provider 추가 시 displayName 누락 방지 가드
    @Test
    @DisplayName("모든 provider에 displayName 존재")
    void everyProviderHasDisplayName() {
        for (IntegrationProvider provider : IntegrationProvider.values()) {
            assertThat(provider.displayName()).isNotBlank();
        }
    }
}
