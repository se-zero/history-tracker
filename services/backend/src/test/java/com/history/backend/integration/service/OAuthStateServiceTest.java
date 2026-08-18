package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.history.backend.security.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OAuthStateService: OAuth 콜백 state 토큰 발급·검증")
class OAuthStateServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final Instant NOW = Instant.parse("2026-06-15T03:00:00Z");
    private static final JwtProperties JWT_PROPERTIES =
            new JwtProperties("test-secret", Duration.ofMinutes(15), Duration.ofDays(14));

    @Test
    @DisplayName("발급한 state 검증 시 projectId·userId·provider 복원")
    void issueAndVerifyRoundTripsClaims() {
        OAuthStateService service = service(NOW);

        String state = service.issue(PROJECT_ID, USER_ID, "slack");
        OAuthStateClaims claims = service.verify(state, "slack");

        assertThat(claims.projectId()).isEqualTo(PROJECT_ID);
        assertThat(claims.userId()).isEqualTo(USER_ID);
        assertThat(claims.provider()).isEqualTo("slack");
    }

    @Test
    @DisplayName("만료된 state 거부")
    void rejectsExpiredState() {
        OAuthStateService issuer = service(NOW);
        String state = issuer.issue(PROJECT_ID, USER_ID, "slack");
        OAuthStateService verifier = service(NOW.plus(Duration.ofMinutes(11)));

        assertThatThrownBy(() -> verifier.verify(state, "slack"))
                .isInstanceOf(OAuthStateException.class);
    }

    @Test
    @DisplayName("서명이 위조된 state 거부")
    void rejectsTamperedSignature() {
        OAuthStateService service = service(NOW);
        String state = service.issue(PROJECT_ID, USER_ID, "slack");
        String[] parts = state.split("\\.");
        String tamperedSignature = (parts[1].charAt(0) == 'a' ? "b" : "a") + parts[1].substring(1);
        String tampered = parts[0] + "." + tamperedSignature;

        assertThatThrownBy(() -> service.verify(tampered, "slack"))
                .isInstanceOf(OAuthStateException.class);
    }

    @Test
    @DisplayName("형식이 잘못된 state 거부")
    void rejectsMalformedState() {
        OAuthStateService service = service(NOW);

        assertThatThrownBy(() -> service.verify("not-a-valid-state", "slack"))
                .isInstanceOf(OAuthStateException.class);
    }

    @Test
    @DisplayName("발급 시점과 다른 provider로 검증 시 거부")
    void rejectsProviderMismatch() {
        OAuthStateService service = service(NOW);
        String state = service.issue(PROJECT_ID, USER_ID, "slack");

        assertThatThrownBy(() -> service.verify(state, "jira"))
                .isInstanceOf(OAuthStateException.class);
    }

    private OAuthStateService service(Instant now) {
        return new OAuthStateService(JWT_PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }
}
