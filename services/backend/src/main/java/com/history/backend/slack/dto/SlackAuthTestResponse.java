package com.history.backend.slack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Slack auth.test 응답 — BYO 토큰 검증용. 실패도 HTTP 200 + ok:false 로 온다.
public record SlackAuthTestResponse(
        Boolean ok,
        String team,
        @JsonProperty("team_id") String teamId,
        String error,
        @JsonProperty("user_id") String userId,
        @JsonProperty("bot_id") String botId
) {
}
