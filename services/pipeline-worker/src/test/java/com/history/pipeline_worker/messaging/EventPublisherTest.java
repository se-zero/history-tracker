package com.history.pipeline_worker.messaging;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * EventPublisher: NormalizedEvent → RabbitMQ 발행 + 소스별 라우팅 키 결정.
 * RabbitTemplate은 Mock으로 대체해 실제 브로커 없이 라우팅 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private EventPublisher publisher;

    // 생성자 주입 방식이므로 테스트에서 직접 값 주입
    private static final String EXCHANGE   = "history.exchange";
    private static final String GITHUB_KEY = "event.github";
    private static final String JIRA_KEY   = "event.jira";
    private static final String SLACK_KEY  = "event.slack";

    @BeforeEach
    void setUp() {
        publisher = new EventPublisher(rabbitTemplate, EXCHANGE, GITHUB_KEY, JIRA_KEY, SLACK_KEY);
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
        NormalizedEvent event = buildEvent("GITHUB");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(EXCHANGE, GITHUB_KEY, event);
    }

    @Test
    @DisplayName("source=JIRA → routing key 'event.jira'로 발행")
    void publishAll_jiraSource_usesJiraRoutingKey() {
        NormalizedEvent event = buildEvent("JIRA");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(EXCHANGE, JIRA_KEY, event);
    }

    @Test
    @DisplayName("source=SLACK → routing key 'event.slack'로 발행")
    void publishAll_slackSource_usesSlackRoutingKey() {
        NormalizedEvent event = buildEvent("SLACK");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(EXCHANGE, SLACK_KEY, event);
    }

    @Test
    @DisplayName("알 수 없는 source → 'event.unknown' 라우팅 키 사용")
    void publishAll_unknownSource_usesUnknownRoutingKey() {
        NormalizedEvent event = buildEvent("UNKNOWN_SYSTEM");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "event.unknown", event);
    }

    @Test
    @DisplayName("source 소문자 입력도 대소문자 무관하게 처리")
    void publishAll_lowercaseSource_caseInsensitive() {
        // resolveRoutingKey는 source.toUpperCase() 후 switch
        NormalizedEvent event = buildEvent("github");

        publisher.publishAll(List.of(event));

        verify(rabbitTemplate).convertAndSend(EXCHANGE, GITHUB_KEY, event);
    }

    // ─── 발행 카운트 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이벤트 N개 발행 → N 반환")
    void publishAll_multipleEvents_returnsCount() {
        List<NormalizedEvent> events = List.of(
                buildEvent("GITHUB"),
                buildEvent("JIRA"),
                buildEvent("SLACK")
        );

        int count = publisher.publishAll(events);

        assertThat(count).isEqualTo(3);
        // 각 이벤트마다 convertAndSend 한 번씩 호출
        verify(rabbitTemplate, times(3)).convertAndSend(eq(EXCHANGE), anyString(), any(NormalizedEvent.class));
    }

    // ─── 헬퍼 메서드 ─────────────────────────────────────────────────────────────

    private NormalizedEvent buildEvent(String source) {
        return new NormalizedEvent(
                "ChangeSet",
                source,
                Instant.now(),
                new ActorDto("user-1", "Test User", "test@example.com"),
                Map.of("hash", "abc123"),
                Map.of()
        );
    }
}
