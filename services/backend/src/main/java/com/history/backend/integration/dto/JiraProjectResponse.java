package com.history.backend.integration.dto;

import com.history.backend.jira.service.JiraClient;

public record JiraProjectResponse(
        String key,
        String name
) {

    public static JiraProjectResponse from(JiraClient.JiraProject project) {
        return new JiraProjectResponse(project.key(), project.name());
    }
}
