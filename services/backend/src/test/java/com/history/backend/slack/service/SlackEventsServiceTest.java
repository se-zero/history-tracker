package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.service.IntegrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlackEventsService: Slack Events API 라이프사이클 이벤트 처리")
class SlackEventsServiceTest {

    private static final String TIMESTAMP = "1756430400";
    private static final String SIGNATURE = "v0=validsig";

    @Mock
    private SlackSignatureVerifier verifier;

    @Mock
    private IntegrationService integrationService;

    // SyncTaskExecutor: execute가 호출 스레드에서 동기 실행 — verify가 즉시 가능하다
    private final SyncTaskExecutor executor = new SyncTaskExecutor();
    // JSON 파싱이 동작의 본질이라 실제 ObjectMapper를 쓴다
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("서명 검증 실패 → UnauthorizedException, integrationService 미호출")
    void handleThrowsUnauthorizedWhenSignatureInvalid() {
        SlackEventsService service = service();
        when(verifier.verify(TIMESTAMP, SIGNATURE, "{}")).thenReturn(false);

        assertThatThrownBy(() -> service.handle(TIMESTAMP, SIGNATURE, "{}"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack request signature.");
        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("url_verification → 즉시 challenge 반환, executor·integrationService 미사용")
    void handleReturnsChallengeSynchronouslyForUrlVerification() {
        SlackEventsService service = service();
        String body = """
                {"token":"unused","challenge":"3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P","type":"url_verification"}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackEventAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.challenge()).isEqualTo("3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P");
        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("url_verification은 executor를 사용하지 않는다 — executor가 거부해도 challenge를 즉시 반환")
    void handleUrlVerificationDoesNotUseExecutor() {
        // executor가 작업을 거부하도록 설정했을 때 url_verification이 여전히 성공하면
        // "executor를 우회해 동기로 처리했다"는 뜻이다
        SlackEventsService service = serviceWithRejectingExecutor();
        String body = """
                {"token":"unused","challenge":"test-challenge-xyz","type":"url_verification"}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackEventAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.challenge()).isEqualTo("test-challenge-xyz");
    }

    @Test
    @DisplayName("event_callback app_uninstalled → executor로 disconnectSlackWorkspace 호출, challenge null")
    void handleDisconnectsWorkspaceOnAppUninstalled() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","team_id":"T123ABC456","api_app_id":"A1",\
"event":{"type":"app_uninstalled"},"type":"event_callback","event_id":"Ev1","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackEventAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.challenge()).isNull();
        verify(integrationService).disconnectSlackWorkspace("T123ABC456");
    }

    @Test
    @DisplayName("tokens_revoked — oauth 사용자만 disconnectSlackUsers 호출, bot 배열은 무시")
    void handleDisconnectsOauthUsersOnTokensRevoked() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","team_id":"T123ABC456","api_app_id":"A1",\
"event":{"type":"tokens_revoked","tokens":{"oauth":["U111","U222"],"bot":["B9"]}},\
"type":"event_callback","event_id":"Ev2","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        service.handle(TIMESTAMP, SIGNATURE, body);

        verify(integrationService).disconnectSlackUsers("T123ABC456", List.of("U111", "U222"));
        // bot 배열은 S1에서 매칭하지 않는다
        verify(integrationService, never()).disconnectSlackWorkspace(any());
    }

    @Test
    @DisplayName("tokens_revoked — oauth 배열이 비어 있으면 disconnectSlackUsers 미호출 (workspace 전체 폴백 없음)")
    void handleSkipsDisconnectWhenOauthUsersEmpty() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","team_id":"T123ABC456","api_app_id":"A1",\
"event":{"type":"tokens_revoked","tokens":{"oauth":[],"bot":["B9"]}},\
"type":"event_callback","event_id":"Ev2","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        service.handle(TIMESTAMP, SIGNATURE, body);

        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("tokens_revoked — oauth 키 자체가 없으면 미호출 (workspace 전체 폴백 없음)")
    void handleSkipsDisconnectWhenOauthKeyMissing() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","team_id":"T123ABC456","api_app_id":"A1",\
"event":{"type":"tokens_revoked","tokens":{"bot":["B9"]}},\
"type":"event_callback","event_id":"Ev2","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        service.handle(TIMESTAMP, SIGNATURE, body);

        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("알 수 없는 event type → 200 Ack, integrationService 미호출 (구독 끊김 방지)")
    void handleIgnoresUnknownEventType() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","team_id":"T123ABC456",\
"event":{"type":"some_unknown_event"},"type":"event_callback","event_id":"Ev3","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackEventAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.challenge()).isNull();
        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 예외 없이 200 Ack 반환 (구독 끊김 방지)")
    void handleIgnoresMalformedJson() {
        SlackEventsService service = service();
        String body = "not-json-at-all";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        SlackEventAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.challenge()).isNull();
        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("app_uninstalled — team_id 없으면 disconnectSlackWorkspace 미호출")
    void handleSkipsDisconnectWhenTeamIdMissing() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","api_app_id":"A1",\
"event":{"type":"app_uninstalled"},"type":"event_callback","event_id":"Ev4","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        service.handle(TIMESTAMP, SIGNATURE, body);

        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("executor가 RejectedExecutionException을 던지면 예외가 전파된다 (Slack 재시도 허용)")
    void handlePropagatesRejectedExecutionException() {
        SlackEventsService service = serviceWithRejectingExecutor();
        String body = """
                {"token":"x","team_id":"T123ABC456","api_app_id":"A1",\
"event":{"type":"app_uninstalled"},"type":"event_callback","event_id":"Ev1","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);

        assertThatThrownBy(() -> service.handle(TIMESTAMP, SIGNATURE, body))
                .isInstanceOf(TaskRejectedException.class);
    }

    @Test
    @DisplayName("비동기 처리 실패는 삼키고 200 Ack — 이미 응답한 뒤라 Slack 재시도가 없고, 스레드만 죽이지 않는다")
    void handleAcksEvenWhenDisconnectThrows() {
        SlackEventsService service = service();
        String body = """
                {"token":"x","team_id":"T123ABC456","api_app_id":"A1",\
"event":{"type":"app_uninstalled"},"type":"event_callback","event_id":"Ev1","event_time":1}""";
        when(verifier.verify(TIMESTAMP, SIGNATURE, body)).thenReturn(true);
        doThrow(new RuntimeException("graph delete failed"))
                .when(integrationService).disconnectSlackWorkspace("T123ABC456");

        SlackEventAck ack = service.handle(TIMESTAMP, SIGNATURE, body);

        assertThat(ack.challenge()).isNull();
        verify(integrationService).disconnectSlackWorkspace("T123ABC456");
    }

    private SlackEventsService service() {
        return new SlackEventsService(verifier, integrationService, executor, objectMapper);
    }

    private SlackEventsService serviceWithRejectingExecutor() {
        return new SlackEventsService(verifier, integrationService, new RejectingTaskExecutor(), objectMapper);
    }

    private static class RejectingTaskExecutor extends SyncTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new TaskRejectedException("slackEventsTaskExecutor is full");
        }
    }
}
