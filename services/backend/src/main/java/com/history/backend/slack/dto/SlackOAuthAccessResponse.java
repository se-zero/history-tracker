package com.history.backend.slack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Slack oauth.v2.access 응답 (POST 성공 시에도 실패를 ok:false로 표현하는 비표준 응답)
public record SlackOAuthAccessResponse(
        Boolean ok,
        String error,
        // 루트 access_token은 bot 토큰(xoxb-) — bot scope 없이 user scope만 있으면 오지 않는다
        @JsonProperty("access_token")
        String accessToken,
        Team team,
        @JsonProperty("authed_user")
        AuthedUser authedUser
) {

    public record Team(
            String id,
            String name
    ) {
    }

    public record AuthedUser(
            String id,
            @JsonProperty("access_token")
            String accessToken
    ) {
    }
}
