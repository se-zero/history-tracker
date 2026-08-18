package com.history.backend.notion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Notion POST /v1/oauth/token 응답
public record NotionTokenResponse(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("bot_id")
        String botId,

        @JsonProperty("workspace_id")
        String workspaceId,

        @JsonProperty("workspace_name")
        String workspaceName
) {
}
