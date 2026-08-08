package com.history.backend.discord.service;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.discord.DiscordProperties;
import com.history.backend.discord.dto.DiscordTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Discord OAuth2 API 클라이언트. code 교환·grant 폐기는 client_id/secret(앱 신원)으로,
// 길드 퇴장은 봇 토큰(수집 주체와 같은 신원)으로 호출한다 — 두 자격증명을 다 다루므로 client가 하나다.
@Slf4j
@Component
public class DiscordClient {

    private final DiscordProperties properties;
    private final RestClient restClient;

    public DiscordClient(
            DiscordProperties properties,
            @Qualifier("discordRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    // authorization code를 refresh token과 봇이 추가된 길드 정보로 교환.
    // access_token은 저장하지 않는다 — 수집은 봇 토큰으로 하고, refresh token만 해제 시 폐기에 쓰인다.
    public DiscordAuthorization exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());

        DiscordTokenResponse response;
        try {
            response = restClient
                    .post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(DiscordTokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (isDefiniteAuthFailure(exception)) {
                throw new UnauthorizedException("Invalid Discord authorization code.");
            }
            throw new BadGatewayException("Discord OAuth code exchange request failed.", exception);
        } catch (RestClientException exception) {
            throw new BadGatewayException("Discord OAuth code exchange request failed.", exception);
        }

        return validateAuthorization(response);
    }

    /**
     * OAuth grant 폐기 (연동 해제 시). refresh token 하나로 그로부터 파생된 access token도 함께
     * 무효화된다.
     *
     * <p>Slack·Jira와 같은 이유로 실패해도 예외를 던지지 않는다 — 이미 폐기됐거나 Discord 장애일 때
     * 연동 해제 자체가 막히면 안 된다.</p>
     */
    public void revokeToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");

        try {
            restClient
                    .post()
                    .uri(properties.revokeUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("Discord token revoke request failed. error={}", exception.getMessage());
        }
    }

    /**
     * 봇을 길드에서 퇴장시킨다. Discord에는 개별 access token 폐기 API가 없어, 사용자 서버에 대한
     * 접근을 실제로 끊는 수단은 이것뿐이다 — 봇 토큰(앱 전체 공유)으로 호출한다.
     *
     * <p>실패해도 예외를 던지지 않는다 — 이미 강퇴됐거나 Discord 장애일 때 연동 해제 자체가
     * 막히면 안 된다.</p>
     */
    public void leaveGuild(String guildId) {
        try {
            restClient
                    .delete()
                    .uri(properties.apiBaseUrl() + "/users/@me/guilds/{guildId}", guildId)
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + properties.botToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("Discord bot guild leave request failed. guildId={} error={}", guildId, exception.getMessage());
        }
    }

    private DiscordAuthorization validateAuthorization(DiscordTokenResponse response) {
        if (response == null || response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BadGatewayException("Discord OAuth response is missing refresh token.");
        }
        DiscordTokenResponse.Guild guild = response.guild();
        if (guild == null || guild.id() == null || guild.id().isBlank() || guild.name() == null || guild.name().isBlank()) {
            throw new BadGatewayException("Discord OAuth response is missing guild information.");
        }
        return new DiscordAuthorization(response.refreshToken(), guild.id(), guild.name());
    }

    // 400·401·403만 확정된 인증 실패로 판정한다(JiraOAuthClient와 같은 기준). 나머지 4xx·5xx는
    // Discord 측 일시 장애로 간주해 BadGateway로 넘긴다.
    private static boolean isDefiniteAuthFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        return status.equals(HttpStatus.BAD_REQUEST)
                || status.equals(HttpStatus.UNAUTHORIZED)
                || status.equals(HttpStatus.FORBIDDEN);
    }

    public record DiscordAuthorization(String refreshToken, String guildId, String guildName) {
    }
}
