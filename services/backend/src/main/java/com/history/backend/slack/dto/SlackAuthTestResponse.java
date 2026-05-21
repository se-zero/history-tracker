package com.history.backend.slack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SlackAuthTestResponse(
        Boolean ok,
        String team,
        @JsonProperty("team_id")
        String teamId,
        String error
) {
}
