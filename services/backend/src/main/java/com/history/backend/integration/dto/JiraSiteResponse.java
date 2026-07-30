package com.history.backend.integration.dto;

import com.history.backend.jira.service.JiraOAuthClient;

public record JiraSiteResponse(
        String cloudId,
        String name,
        String url
) {

    public static JiraSiteResponse from(JiraOAuthClient.JiraSite site) {
        return new JiraSiteResponse(site.cloudId(), site.name(), site.url());
    }
}
