package com.history.backend.discord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Discord OAuth2 토큰 교환 응답. access_token·expires_in·scope는 쓰지 않아 매핑하지 않는다 —
// 수집은 봇 토큰으로 하고, 저장하는 자격증명은 해제 시 폐기에 쓰는 refresh_token뿐이다.
public record DiscordTokenResponse(
        @JsonProperty("refresh_token")
        String refreshToken,

        Guild guild
) {

    public record Guild(
            String id,
            String name
    ) {
    }
}
