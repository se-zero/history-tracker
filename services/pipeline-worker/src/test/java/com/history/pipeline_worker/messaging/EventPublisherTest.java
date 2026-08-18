package com.history.pipeline_worker.messaging;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * EventPublisher: NormalizedEvent → RabbitMQ 발행 + 소스별 라우팅 키 결정 + publisher confirm 검증.
 * RabbitTemplate은 Mock으로 대체하고, convertAndSend 스텁이 CorrelationData future를 완료시켜 broker confirm을 흉내낸다.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private EventPublisher publisher;

    // 생성자 주입 방식이므로 테스트에서 직접 값 주입
    private static final String EXCHANGE   = "history.exchange";
    private static final String PREFIX     = "event";
    private static final String GITHUB_KEY = "event.github";
    private static final String JIRA_KEY   = "event.jira";
    private static final String SLACK_KEY  = "event.slack";
    // 타임아웃 테스트가 빨리 끝나도록 짧게 설정
    private static final long CONFIRM_TIMEOUT_MS = 200;

    @BeforeEach
    void setUp() {
        publisher = new EventPublisher(rabbitTemplate, EXCHANGE, PREFIX, CONFIRM_TIMEOUT_MS);
    }

    // convertAndSend 호출 시 CorrelationData future를 주어진 결과로 완료시키는 스텁
    private void stubConfirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        MessageBuilder.withBody(new byte[0]).build(), 312, "NO_ROUTE", EXCHANGE, GITHUB_KEY));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nacked"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(NormalizedEvent.class), any(CorrelationData.class));
    }

    // ─── 빈 / null 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 이벤트 목록 → 0 반환, RabbitTemplate 미호출")
    void publishAll_nullList_returnsZeroAndNoInteraction() {
        int count = publisher.publishAll(null);

        assertThat(count).isZero();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("빈 이벤트 목록 → 0 반환, RabbitTemplate 미호출")
    void publishAll_emptyList_returnsZeroAndNoInteraction() {
        int count = publisher.publishAll(List.of());

        assertThat(count).isZero();
        verifyNoInteractions(rabbitTemplate);
    }

    // ─── 라우팅 키 결정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("source=GITHUB → routing key 'event.github'로 발행")
    void publishAll_githubSource_usesGithubRoutingKey() {
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("GITHUB");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(GITHUB_KEY), eq(event), any(CorrelationData.class));
    }

    @Test
    @DisplayName("source=JIRA → routing key 'event.jira'로 발행")
    void publishAll_jiraSource_usesJiraRoutingKey() {
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("JIRA");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(JIRA_KEY), eq(event), any(CorrelationData.class));
    }

    @Test
    @DisplayName("source=SLACK → routing key 'event.slack'로 발행")
    void publishAll_slackSource_usesSlackRoutingKey() {
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("SLACK");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(SLACK_KEY), eq(event), any(CorrelationData.class));
    }

    @Test
    @DisplayName("신규 source는 발행기 수정 없이 'event.{소문자}'로 라우팅된다")
    void publishAll_newSource_derivesRoutingKeyWithoutRegistration() {
        // 커넥터 추가 시 이 클래스를 고치지 않는다는 계약 — provider 분기를 되살리면 이 테스트가 깨진다
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("LINEAR");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("event.linear"), eq(event), any(CorrelationData.class));
    }

    @Test
    @DisplayName("두 단어 source는 대문자 snake → 소문자 snake로 유도 (GOOGLE_CHAT → event.google_chat)")
    void publishAll_snakeCaseSource_keepsUnderscore() {
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("GOOGLE_CHAT");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate)
                .convertAndSend(eq(EXCHANGE), eq("event.google_chat"), eq(event), any(CorrelationData.class));
    }

    @Test
    @DisplayName("source가 비면 'event.unknown'으로 발행 (라우팅 키가 'event.'로 끝나지 않게)")
    void publishAll_blankSource_usesUnknownRoutingKey() {
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("  ");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("event.unknown"), eq(event), any(CorrelationData.class));
    }

    @Test
    @DisplayName("source 소문자 입력도 대소문자 무관하게 처리")
    void publishAll_lowercaseSource_caseInsensitive() {
        stubConfirm(true, false);
        NormalizedEvent event = buildEvent("github");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(GITHUB_KEY), eq(event), any(CorrelationData.class));
    }

    // ─── 발행 카운트 (전부 confirm) ─────────────────────────────────────────────────

    @Test
    @DisplayName("이벤트 N개 전부 ack → N 반환")
    void publishAll_multipleEvents_allAcked_returnsCount() {
        stubConfirm(true, false);
        List<NormalizedEvent> events = List.of(
                buildEvent("GITHUB"),
                buildEvent("JIRA"),
                buildEvent("SLACK")
        );

        int count = publisher.publishAll(events);

        assertThat(count).isEqualTo(3);
        verify(rabbitTemplate, times(3))
                .convertAndSend(eq(EXCHANGE), anyString(), any(NormalizedEvent.class), any(CorrelationData.class));
    }

    // ─── confirm 실패 → 예외 (checkpoint 전진 차단) ─────────────────────────────────

    @Test
    @DisplayName("브로커 nack → EventPublishException")
    void publishAll_nack_throws() {
        stubConfirm(false, false);

        assertThatThrownBy(() -> publisher.publishAll(List.of(buildEvent("GITHUB"))))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("ack하지 않음");
    }

    @Test
    @DisplayName("라우팅 실패(unroutable, ack=true + returned) → EventPublishException")
    void publishAll_unroutable_throws() {
        stubConfirm(true, true);

        assertThatThrownBy(() -> publisher.publishAll(List.of(buildEvent("GITHUB"))))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("unroutable");
    }

    @Test
    @DisplayName("confirm 미수신(타임아웃) → EventPublishException")
    void publishAll_confirmTimeout_throws() {
        // future를 완료시키지 않아 타임아웃 유도
        doNothing().when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(NormalizedEvent.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publishAll(List.of(buildEvent("GITHUB"))))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("타임아웃");
    }

    // ─── 헬퍼 메서드 ─────────────────────────────────────────────────────────────

    private NormalizedEvent buildEvent(String source) {
        return new NormalizedEvent(
                "11111111-1111-1111-1111-111111111111",
                "ChangeSet",
                source,
                Instant.now(),
                new ActorDto("user-1", "Test User", "test@example.com"),
                Map.of("hash", "abc123"),
                Map.of()
        );
    }
}
