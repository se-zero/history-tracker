package com.history.backend.discord.service;

import java.util.Map;

import com.history.backend.discord.DiscordProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.integration.service.OAuthConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class DiscordOAuthConnectFlow implements OAuthConnectFlow {

    // 이 flow가 담아 넣는 external_ref 키 — pipeline-worker가 수집할 때 읽는 키와 같아야 한다
    public static final String GUILD_ID = "guild_id";
    public static final String GUILD_NAME = "guild_name";

    private final DiscordProperties discordProperties;
    private final DiscordClient discordClient;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.DISCORD;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(discordProperties.authorizeUrl())
                .queryParam("client_id", discordProperties.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", discordProperties.redirectUri())
                .queryParam("scope", discordProperties.scopes())
                .queryParam("permissions", discordProperties.permissions())
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    // Discord는 선택 단계가 없다 — 동의 화면에서 봇을 추가할 서버를 직접 고르므로,
    // 토큰 교환 응답의 guild가 곧 수집 대상이다.
    @Override
    public OAuthConnection exchangeCode(String code) {
        DiscordClient.DiscordAuthorization authorization = discordClient.exchangeCode(code);
        return new OAuthConnection(
                authorization.refreshToken(),
                Map.of(
                        GUILD_ID, authorization.guildId(),
                        GUILD_NAME, authorization.guildName()
                )
        );
    }
}
