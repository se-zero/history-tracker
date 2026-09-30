package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.oauth.dto.OAuthGrantResponse;
import com.history.backend.oauth.repository.OAuthGrantRepository;
import com.history.backend.oauth.repository.OAuthGrantRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthGrantService: 연결된 앱 목록·철회")
class OAuthGrantServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final Instant GRANTED_AT = Instant.parse("2026-09-20T03:12:00Z");
    private static final Instant LAST_USED_AT = Instant.parse("2026-09-21T05:00:00Z");

    @Mock
    private OAuthGrantRepository oAuthGrantRepository;

    @Mock
    private RegisteredClientRepository registeredClientRepository;

    @Test
    @DisplayName("행마다 앱 정보를 붙여 응답으로 매핑 — client_uri 설정이 있으면 clientUri 값")
    void listMapsRowWithRegisteredClientInfo() {
        RegisteredClient client = registeredClient("internal-id-1", "client-id-1", "Claude Code", "https://claude.ai");
        when(oAuthGrantRepository.findActiveByPrincipal(any(), any()))
                .thenReturn(List.of(new OAuthGrantRow("internal-id-1", GRANTED_AT, LAST_USED_AT)));
        when(registeredClientRepository.findById("internal-id-1")).thenReturn(client);

        List<OAuthGrantResponse> result = oAuthGrantService().list(USER_ID);

        assertThat(result).containsExactly(new OAuthGrantResponse(
                "internal-id-1", "client-id-1", "Claude Code", "https://claude.ai", GRANTED_AT, LAST_USED_AT));
    }

    @Test
    @DisplayName("client_uri 설정이 없는 앱은 clientUri null")
    void listMapsNullClientUriWhenClientHasNone() {
        RegisteredClient client = registeredClient("internal-id-1", "client-id-1", "Claude Code", null);
        when(oAuthGrantRepository.findActiveByPrincipal(any(), any()))
                .thenReturn(List.of(new OAuthGrantRow("internal-id-1", GRANTED_AT, null)));
        when(registeredClientRepository.findById("internal-id-1")).thenReturn(client);

        List<OAuthGrantResponse> result = oAuthGrantService().list(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).clientUri()).isNull();
        assertThat(result.get(0).lastUsedAt()).isNull();
    }

    @Test
    @DisplayName("조회 principal은 userId 문자열, now는 현재 시각")
    void listQueriesWithUserIdStringAndCurrentTime() {
        when(oAuthGrantRepository.findActiveByPrincipal(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        oAuthGrantService().list(USER_ID);

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(oAuthGrantRepository).findActiveByPrincipal(eq(USER_ID.toString()), now.capture());
        assertThat(now.getValue()).isBetween(before, Instant.now().plus(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("앱 행이 없는(findById null) 항목은 빼고 나머지는 유지")
    void listSkipsRowsWhoseRegisteredClientIsMissing() {
        RegisteredClient kept = registeredClient("internal-id-2", "client-id-2", "Cursor", null);
        when(oAuthGrantRepository.findActiveByPrincipal(any(), any())).thenReturn(List.of(
                new OAuthGrantRow("internal-id-1", GRANTED_AT, LAST_USED_AT),
                new OAuthGrantRow("internal-id-2", GRANTED_AT, LAST_USED_AT)));
        when(registeredClientRepository.findById("internal-id-1")).thenReturn(null);
        when(registeredClientRepository.findById("internal-id-2")).thenReturn(kept);

        List<OAuthGrantResponse> result = oAuthGrantService().list(USER_ID);

        assertThat(result).extracting(OAuthGrantResponse::id).containsExactly("internal-id-2");
    }

    @Test
    @DisplayName("연결이 없으면 빈 목록")
    void listReturnsEmptyWhenNoActiveGrants() {
        when(oAuthGrantRepository.findActiveByPrincipal(any(), any())).thenReturn(List.of());

        assertThat(oAuthGrantService().list(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("철회는 userId 문자열과 grantId로 저장소에 위임")
    void revokeDelegatesToRepository() {
        when(oAuthGrantRepository.deleteByPrincipalAndClient(USER_ID.toString(), "internal-id-1")).thenReturn(2);

        oAuthGrantService().revoke(USER_ID, "internal-id-1");

        verify(oAuthGrantRepository).deleteByPrincipalAndClient(USER_ID.toString(), "internal-id-1");
        verifyNoMoreInteractions(oAuthGrantRepository);
    }

    @Test
    @DisplayName("지울 행이 없어(0 반환) 도 예외 없이 끝난다(멱등)")
    void revokeIsIdempotentWhenNothingDeleted() {
        when(oAuthGrantRepository.deleteByPrincipalAndClient(USER_ID.toString(), "missing")).thenReturn(0);

        assertThatCode(() -> oAuthGrantService().revoke(USER_ID, "missing")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전체 철회는 userId 문자열로 사용자 전체 삭제에 위임")
    void revokeAllDelegatesToRepositoryWithUserIdString() {
        when(oAuthGrantRepository.deleteByPrincipal(USER_ID.toString())).thenReturn(3);

        oAuthGrantService().revokeAll(USER_ID);

        verify(oAuthGrantRepository).deleteByPrincipal(USER_ID.toString());
        verifyNoMoreInteractions(oAuthGrantRepository);
    }

    @Test
    @DisplayName("만료 정리는 현재 시각 기준으로 위임하고 저장소가 지운 행 수를 그대로 반환")
    void purgeExpiredDelegatesWithCurrentTimeAndReturnsDeletedCount() {
        when(oAuthGrantRepository.deleteExpired(any())).thenReturn(7);
        Instant before = Instant.now();

        int purged = oAuthGrantService().purgeExpired();

        assertThat(purged).isEqualTo(7);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(oAuthGrantRepository).deleteExpired(now.capture());
        assertThat(now.getValue()).isBetween(before, Instant.now().plus(Duration.ofSeconds(1)));
        verifyNoMoreInteractions(oAuthGrantRepository);
    }

    private OAuthGrantService oAuthGrantService() {
        return new OAuthGrantService(oAuthGrantRepository, registeredClientRepository);
    }

    private RegisteredClient registeredClient(String id, String clientId, String clientName, String clientUri) {
        ClientSettings.Builder settings = ClientSettings.builder();
        if (clientUri != null) {
            settings.setting(McpRegisteredClientPolicy.CLIENT_URI_SETTING, clientUri);
        }
        return RegisteredClient.withId(id)
                .clientId(clientId)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/callback")
                .clientSettings(settings.build())
                .build();
    }
}
