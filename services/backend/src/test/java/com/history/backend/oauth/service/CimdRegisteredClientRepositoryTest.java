package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.history.backend.oauth.McpOAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("CimdRegisteredClientRepository: CIMD client_id를 그림자 RegisteredClient로 upsert")
class CimdRegisteredClientRepositoryTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String URL = "https://cimd.example/client";

    @Mock
    private RegisteredClientRepository delegate;

    @Mock
    private CimdDocumentFetcher fetcher;

    private final McpRegisteredClientPolicy policy =
            new McpRegisteredClientPolicy(new McpOAuthProperties("http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30)));

    @Test
    @DisplayName("같은 URL의 최초 등록이 동시에 일어나 INSERT가 PK 중복으로 실패해도 조립 결과를 돌려준다")
    void findByClientIdToleratesConcurrentFirstInsert() {
        CimdRegisteredClientRepository repository = repository();
        String shadowId = CimdRegisteredClientRepository.shadowId(URL);
        when(fetcher.fetch(URL)).thenReturn(new CimdClientMetadata(
                URL, "Claude Code", "https://claude.ai", Set.of("http://localhost/callback")));
        when(delegate.findById(shadowId)).thenReturn(null);
        doThrow(new DuplicateKeyException("duplicate id")).when(delegate).save(any());

        RegisteredClient result = repository.findByClientId(URL);

        assertThat(result.getId()).isEqualTo(shadowId);
        assertThat(result.getClientId()).isEqualTo(URL);
    }

    @Test
    @DisplayName("https client_id + 새 문서 → 조립해 저장(clientIdIssuedAt=지금)")
    void findByClientIdFetchesAndSavesNewShadowClient() {
        CimdRegisteredClientRepository repository = repository();
        String shadowId = CimdRegisteredClientRepository.shadowId(URL);
        when(fetcher.fetch(URL)).thenReturn(new CimdClientMetadata(
                URL, "Claude Code", "https://claude.ai", Set.of("http://localhost/callback")));
        when(delegate.findById(shadowId)).thenReturn(null);

        RegisteredClient result = repository.findByClientId(URL);

        assertThat(result.getId()).isEqualTo(shadowId);
        assertThat(result.getClientId()).isEqualTo(URL);
        assertThat(result.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(result.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(result.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(result.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofHours(1));
        assertThat(result.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(30));
        assertThat(result.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(result.getScopes()).containsExactly(McpRegisteredClientPolicy.SCOPE);
        assertThat(result.getRedirectUris()).containsExactly("http://localhost/callback");
        assertThat(result.getClientName()).isEqualTo("Claude Code");
        assertThat(McpRegisteredClientPolicy.clientUriOf(result)).isEqualTo("https://claude.ai");
        assertThat(result.getClientIdIssuedAt()).isEqualTo(FIXED_INSTANT);

        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(delegate).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(shadowId);
        assertThat(captor.getValue().getClientIdIssuedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("shadowId — 같은 URL은 같은 값, 다른 URL은 다른 값, URL-safe(패딩 없는) Base64")
    void shadowIdIsDeterministicAndUrlSafe() {
        String otherUrl = "https://cimd.example/other";

        String id1 = CimdRegisteredClientRepository.shadowId(URL);
        String id1Again = CimdRegisteredClientRepository.shadowId(URL);
        String id2 = CimdRegisteredClientRepository.shadowId(otherUrl);

        assertThat(id1).isEqualTo(id1Again);
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("기존 행과 redirectUris·clientName·client_uri가 모두 같으면 save 없이 그대로 반환")
    void findByClientIdSkipsSaveWhenExistingRowUnchanged() {
        CimdRegisteredClientRepository repository = repository();
        String shadowId = CimdRegisteredClientRepository.shadowId(URL);
        Instant existingIssuedAt = Instant.parse("2025-01-01T00:00:00Z");
        RegisteredClient existing = policy.apply(
                        RegisteredClient.withId(shadowId).clientId(URL).clientName("Claude Code")
                                .redirectUri("http://localhost/callback"),
                        "https://claude.ai")
                .clientIdIssuedAt(existingIssuedAt)
                .build();
        when(fetcher.fetch(URL)).thenReturn(new CimdClientMetadata(
                URL, "Claude Code", "https://claude.ai", Set.of("http://localhost/callback")));
        when(delegate.findById(shadowId)).thenReturn(existing);

        RegisteredClient result = repository.findByClientId(URL);

        assertThat(result.getRedirectUris()).containsExactly("http://localhost/callback");
        assertThat(result.getClientName()).isEqualTo("Claude Code");
        verify(delegate, never()).save(any());
    }

    @Test
    @DisplayName("기존 행과 redirectUris가 다르면 저장하되 clientIdIssuedAt은 기존 값을 유지")
    void findByClientIdSavesWithPreservedIssuedAtWhenRedirectUrisChanged() {
        CimdRegisteredClientRepository repository = repository();
        String shadowId = CimdRegisteredClientRepository.shadowId(URL);
        Instant existingIssuedAt = Instant.parse("2025-01-01T00:00:00Z");
        RegisteredClient existing = policy.apply(
                        RegisteredClient.withId(shadowId).clientId(URL).clientName("Claude Code")
                                .redirectUri("http://localhost/old-callback"),
                        "https://claude.ai")
                .clientIdIssuedAt(existingIssuedAt)
                .build();
        when(fetcher.fetch(URL)).thenReturn(new CimdClientMetadata(
                URL, "Claude Code", "https://claude.ai", Set.of("http://localhost/new-callback")));
        when(delegate.findById(shadowId)).thenReturn(existing);

        RegisteredClient result = repository.findByClientId(URL);

        assertThat(result.getRedirectUris()).containsExactly("http://localhost/new-callback");
        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(delegate).save(captor.capture());
        assertThat(captor.getValue().getClientIdIssuedAt()).isEqualTo(existingIssuedAt);
        assertThat(captor.getValue().getRedirectUris()).containsExactly("http://localhost/new-callback");
    }

    @Test
    @DisplayName("문서 조회 일시 장애 + 기존 행 있음 → 기존 행 반환, save 없음")
    void findByClientIdReturnsExistingRowWhenFetchUnavailable() {
        CimdRegisteredClientRepository repository = repository();
        String shadowId = CimdRegisteredClientRepository.shadowId(URL);
        RegisteredClient existing = policy.apply(
                        RegisteredClient.withId(shadowId).clientId(URL).clientName("Claude Code")
                                .redirectUri("http://localhost/callback"),
                        "https://claude.ai")
                .build();
        when(fetcher.fetch(URL)).thenThrow(new CimdDocumentUnavailableException("timeout"));
        when(delegate.findById(shadowId)).thenReturn(existing);

        RegisteredClient result = repository.findByClientId(URL);

        assertThat(result).isSameAs(existing);
        verify(delegate, never()).save(any());
    }

    @Test
    @DisplayName("문서 조회 일시 장애 + 기존 행 없음 → null")
    void findByClientIdReturnsNullWhenFetchUnavailableAndNoExistingRow() {
        CimdRegisteredClientRepository repository = repository();
        String shadowId = CimdRegisteredClientRepository.shadowId(URL);
        when(fetcher.fetch(URL)).thenThrow(new CimdDocumentUnavailableException("timeout"));
        when(delegate.findById(shadowId)).thenReturn(null);

        assertThat(repository.findByClientId(URL)).isNull();
        verify(delegate, never()).save(any());
    }

    @Test
    @DisplayName("문서가 영구 무효 → null, save 없음(기존 행이 있어도 쓰지 않는다)")
    void findByClientIdReturnsNullWhenDocumentInvalid() {
        CimdRegisteredClientRepository repository = repository();
        when(fetcher.fetch(URL)).thenThrow(new CimdDocumentInvalidException("bad document"));

        assertThat(repository.findByClientId(URL)).isNull();
        verify(delegate, never()).save(any());
    }

    @Test
    @DisplayName("http:// 또는 무작위 client_id는 fetcher를 호출하지 않고 delegate에 위임")
    void findByClientIdDelegatesWithoutFetchingForNonHttpsClientId() {
        CimdRegisteredClientRepository repository = repository();
        RegisteredClient existing = RegisteredClient.withId("id").clientId("abc123")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/cb")
                .build();
        when(delegate.findByClientId("http://x/doc")).thenReturn(null);
        when(delegate.findByClientId("abc123")).thenReturn(existing);

        assertThat(repository.findByClientId("http://x/doc")).isNull();
        assertThat(repository.findByClientId("abc123")).isSameAs(existing);
        verifyNoInteractions(fetcher);
    }

    @Test
    @DisplayName("findById·save는 delegate에 그대로 위임")
    void findByIdAndSaveDelegateToUnderlyingRepository() {
        CimdRegisteredClientRepository repository = repository();
        RegisteredClient client = RegisteredClient.withId("id").clientId("c")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/cb")
                .build();
        when(delegate.findById("id")).thenReturn(client);

        assertThat(repository.findById("id")).isSameAs(client);
        repository.save(client);
        verify(delegate).save(client);
    }

    // ── 헬퍼 ──

    private CimdRegisteredClientRepository repository() {
        return new CimdRegisteredClientRepository(delegate, fetcher, policy, CLOCK);
    }
}
