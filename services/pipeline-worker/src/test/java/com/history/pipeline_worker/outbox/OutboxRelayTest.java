package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.outbox.OutboxRepository.OutboxRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay: 미발행 아웃박스 행 → RabbitMQ 일괄 전송 + id 순 confirm 확인 + 첫 실패 직전까지의 접두사만 발행 표시.
 * RabbitTemplate·OutboxRepository는 Mock으로 대체하고, send 스텁이 CorrelationData future를 완료시켜 broker confirm을 흉내낸다.
 * 스텁은 "n번째 send 호출(0부터, 배치를 가로질러 누적)"의 결과를 지정하며 지정 없는 호출은 ack다.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxRepository outbox;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private static final String EXCHANGE = "history.exchange";
    private static final String PREFIX = "event";
    // 타임아웃 케이스가 빨리 끝나도록 짧게 설정 (기본값 10초면 그 테스트가 10초씩 걸린다)
    private static final long CONFIRM_TIMEOUT_MS = 200;
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private enum Outcome { ACK, NACK, UNROUTABLE, PENDING, SEND_FAILS }

    // n번째 send 호출의 CorrelationData future를 지정한 결과로 완료시키는 스텁
    private void stubConfirms(Map<Integer, Outcome> outcomes) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            Outcome outcome = outcomes.getOrDefault(calls.getAndIncrement(), Outcome.ACK);
            CorrelationData correlation = invocation.getArgument(3);
            switch (outcome) {
                case SEND_FAILS -> throw new AmqpException("connection lost");
                case PENDING -> { /* future를 완료하지 않아 타임아웃 */ }
                case UNROUTABLE -> {
                    correlation.setReturned(new ReturnedMessage(
                            MessageBuilder.withBody(new byte[0]).build(), 312, "NO_ROUTE", EXCHANGE, "event.github"));
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                }
                case NACK -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nacked"));
                default -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void stubConfirms(int index, Outcome outcome) {
        stubConfirms(Map.of(index, outcome));
    }

    // ─── 빈 목록 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("미발행 행이 없으면 → 0 반환, send·markPublished 미호출")
    void drain_noRows_returnsZeroAndNoSend() {
        when(outbox.fetchUnpublished(OutboxRelay.BATCH_SIZE)).thenReturn(List.of());

        int published = relay().drain();

        assertThat(published).isZero();
        verifyNoInteractions(rabbitTemplate);
        verify(outbox, never()).markPublished(any());
    }

    // ─── 라우팅 키 / 메시지 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("source별 routing key 결정 — 소문자 변환, 공백은 unknown")
    void publishBatch_sources_resolveRoutingKeys() {
        stubConfirms(Map.of());
        List<String> sources = List.of("GITHUB", "JIRA", "SLACK", "LINEAR", "GOOGLE_CHAT", "", "github");

        relay().publishBatch(rowsWithSources(sources));

        ArgumentCaptor<String> routingKeys = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate, times(7)).send(anyString(), routingKeys.capture(), any(Message.class), any(CorrelationData.class));
        assertThat(routingKeys.getAllValues()).containsExactly(
                "event.github", "event.jira", "event.slack", "event.linear",
                "event.google_chat", "event.unknown", "event.github");
    }

    @Test
    @DisplayName("메시지 — body는 payload UTF-8 바이트, JSON·UTF-8·PERSISTENT, exchange와 correlation id가 맞다")
    void publishBatch_message_hasWireFormat() {
        stubConfirms(Map.of());
        String payload = "{\"title\":\"한글 payload\"}";

        relay().publishBatch(List.of(new OutboxRow(42, PROJECT_ID, "GITHUB", payload)));

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq(EXCHANGE), anyString(), message.capture(), correlation.capture());
        assertThat(message.getValue().getBody()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getValue().getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(correlation.getValue().getId()).isEqualTo("42");
    }

    // ─── confirm 접두사 표시 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("3건 전부 ack → markPublished([1,2,3]) 1회, 3 반환")
    void publishBatch_allAcked_marksAll() {
        stubConfirms(Map.of());

        int published = relay().publishBatch(rows(1, 3));

        assertThat(published).isEqualTo(3);
        verify(outbox, times(1)).markPublished(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("2번째 nack → 3건 모두 전송하되 접두사 [1]만 표시, 1 반환")
    void publishBatch_secondNacked_marksPrefixOnly() {
        stubConfirms(1, Outcome.NACK);

        int published = relay().publishBatch(rows(1, 3));

        assertThat(published).isEqualTo(1);
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(outbox).markPublished(List.of(1L));
        verify(outbox, times(1)).markPublished(any());
    }

    @Test
    @DisplayName("2번째 unroutable(ack + returned) → nack와 동일하게 접두사 [1]만 표시")
    void publishBatch_secondUnroutable_marksPrefixOnly() {
        stubConfirms(1, Outcome.UNROUTABLE);

        int published = relay().publishBatch(rows(1, 3));

        assertThat(published).isEqualTo(1);
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(outbox).markPublished(List.of(1L));
        verify(outbox, times(1)).markPublished(any());
    }

    @Test
    @DisplayName("1번째 confirm 타임아웃 → markPublished 미호출, 0 반환")
    void publishBatch_firstTimesOut_marksNothing() {
        stubConfirms(0, Outcome.PENDING);

        int published = relay().publishBatch(rows(1, 3));

        assertThat(published).isZero();
        verify(outbox, never()).markPublished(any());
    }

    @Test
    @DisplayName("3번째 send가 AmqpException → 전송 중단, 1·2번 confirm 확인 후 [1,2] 표시, 예외 비전파")
    void publishBatch_sendThrows_marksAlreadyConfirmedPrefix() {
        stubConfirms(2, Outcome.SEND_FAILS);
        OutboxRelay relay = relay();
        List<OutboxRow> rows = rows(1, 4);

        AtomicInteger published = new AtomicInteger(-1);
        assertThatCode(() -> published.set(relay.publishBatch(rows))).doesNotThrowAnyException();

        assertThat(published.get()).isEqualTo(2);
        // 3번째에서 중단했으므로 4번째는 전송하지 않는다
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(outbox).markPublished(List.of(1L, 2L));
    }

    // ─── drain ───────────────────────────────────────────────────────────────────

    // 종료 조건이 깨지면 fetch 스텁이 마지막 값을 반복해 무한 루프가 되므로, 스위트가 멈추는 대신 실패하게 한다
    @Test
    @Timeout(10)
    @DisplayName("drain — 200건 전부 성공 후 50건 전부 성공 → fetch 2회(두 번째가 가득 차지 않아 종료), 250 반환")
    void drain_fullBatchThenPartial_stopsAfterPartial() {
        stubConfirms(Map.of());
        when(outbox.fetchUnpublished(OutboxRelay.BATCH_SIZE))
                .thenReturn(rows(1, 200))
                .thenReturn(rows(201, 50));

        int published = relay().drain();

        assertThat(published).isEqualTo(250);
        verify(outbox, times(2)).fetchUnpublished(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @Timeout(10)
    @DisplayName("drain — 200건 중 100번째 nack → 다음 배치를 가져오지 않고 99 반환")
    void drain_failureInBatch_stopsWithoutNextFetch() {
        stubConfirms(99, Outcome.NACK);
        when(outbox.fetchUnpublished(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 200));

        int published = relay().drain();

        assertThat(published).isEqualTo(99);
        verify(outbox, times(1)).fetchUnpublished(OutboxRelay.BATCH_SIZE);
    }

    // ─── pump 예외 격리 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("pump — DB 예외가 나도 전파하지 않는다(스케줄러 스레드 보호)")
    void pump_dataAccessException_isSwallowed() {
        when(outbox.fetchUnpublished(OutboxRelay.BATCH_SIZE))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────────

    private OutboxRelay relay() {
        return new OutboxRelay(outbox, rabbitTemplate, EXCHANGE, PREFIX, CONFIRM_TIMEOUT_MS);
    }

    // id = firstId..firstId+count-1, source GITHUB
    private List<OutboxRow> rows(long firstId, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new OutboxRow(firstId + i, PROJECT_ID, "GITHUB", "{\"n\":" + (firstId + i) + "}"))
                .collect(Collectors.toList());
    }

    // id = 1..n, source는 목록 순서대로
    private List<OutboxRow> rowsWithSources(List<String> sources) {
        List<OutboxRow> rows = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            rows.add(new OutboxRow(i + 1, PROJECT_ID, sources.get(i), "{}"));
        }
        return rows;
    }
}
