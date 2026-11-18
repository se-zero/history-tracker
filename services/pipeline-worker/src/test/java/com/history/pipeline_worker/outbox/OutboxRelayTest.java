package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.alert.OperatorAlert;
import com.history.pipeline_worker.outbox.OutboxRepository.OutboxRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay: 한 틱(pump) = ① 재시도 단계(실패 행을 attempts·next_attempt_at 기준으로 한 건씩 재전송) → ② 정상 단계(미시도 행 묶음 전송).
 * 정상 단계는 ack 행만 발행 표시하고 nack 행만 실패 기록하며, 확인 응답 시간 초과·전송 못 한 행은 건드리지 않는다(접두사 표시 없음).
 * 브로커 문제 틱(큐 선언 실패·전송 예외·시간 초과)이 이어지면 다음 pump를 1·2·4·8·16·30초로 건너뛴다.
 * RabbitTemplate·OutboxRepository·OperatorAlert는 Mock으로 대체하고, send 스텁이 CorrelationData future를 완료시켜 broker confirm을 흉내낸다.
 * 스텁은 "n번째 send 호출(0부터, 재시도 단계 → 정상 단계 순으로 틱·묶음을 가로질러 누적)"의 결과를 지정하며 지정 없는 호출은 ack다.
 * 시계는 테스트가 직접 옮기는 MutableClock이다.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxRepository outbox;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private OperatorAlert operatorAlert;

    // 타임아웃 케이스가 빨리 끝나도록 짧게 설정 (기본값 10초면 그 테스트가 10초씩 걸린다)
    private static final long CONFIRM_TIMEOUT_MS = 200;
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant BASE_INSTANT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Duration ONE_MS = Duration.ofMillis(1);

    private final MutableClock clock = new MutableClock(BASE_INSTANT);

    private enum Outcome { ACK, NACK, UNROUTABLE, PENDING, SEND_FAILS, FUTURE_FAILS, INTERRUPTS }

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
                            MessageBuilder.withBody(new byte[0]).build(), 312, "NO_ROUTE", "", "history.project." + PROJECT_ID));
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                }
                case NACK -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nacked"));
                case FUTURE_FAILS -> correlation.getFuture().completeExceptionally(new RuntimeException("confirm broken"));
                case INTERRUPTS -> Thread.currentThread().interrupt(); // future는 완료하지 않아 get이 곧바로 InterruptedException을 던진다
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
    @DisplayName("재시도 대상도 미발행 행도 없으면 → send 미호출, 발행 표시·실패 기록 없음")
    void pump_noRows_doesNothing() {
        relay().pump();

        verifyNoInteractions(rabbitTemplate);
        verifyNoMarkPublished();
        verifyNoRecordFailure();
        verifyNoInteractions(operatorAlert);
    }

    // ─── 저장소 호출 규약 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("재시도 대상은 BATCH_SIZE로, 정상 묶음도 BATCH_SIZE로 읽는다")
    void pump_readsBothFetchesWithBatchSize() {
        relay().pump();

        verify(outbox).fetchDueRetries(OutboxRelay.BATCH_SIZE);
        verify(outbox).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("한 틱에서 fetchDueRetries가 fetchFresh보다 먼저 불린다")
    void pump_retriesAreFetchedBeforeFresh() {
        relay().pump();

        InOrder order = inOrder(outbox);
        order.verify(outbox).fetchDueRetries(OutboxRelay.BATCH_SIZE);
        order.verify(outbox).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    // ─── 라우팅 키 / 메시지 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트 큐를 durable·single-active-consumer 인자만으로 선언한 뒤 전송한다")
    void pump_declaresProjectQueue_thenSends() {
        stubConfirms(Map.of());
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));

        relay().pump();

        InOrder order = inOrder(amqpAdmin, rabbitTemplate);
        ArgumentCaptor<Queue> queue = ArgumentCaptor.forClass(Queue.class);
        order.verify(amqpAdmin).declareQueue(queue.capture());
        order.verify(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(queue.getValue().getName()).isEqualTo("history.project." + PROJECT_ID);
        assertThat(queue.getValue().isDurable()).isTrue();
        assertThat(queue.getValue().isExclusive()).isFalse();
        assertThat(queue.getValue().isAutoDelete()).isFalse();
        // ai-engine이 같은 큐를 passive로 확인하므로 인자가 하나라도 다르면 브로커가 406으로 채널을 닫는다 — 정확히 일치해야 한다
        assertThat(queue.getValue().getArguments()).isEqualTo(Map.of("x-single-active-consumer", true));
    }

    @Test
    @DisplayName("같은 프로젝트 2행 + 다른 프로젝트 1행 → 큐 선언은 프로젝트당 1회(첫 등장 순서), 전송 3회는 각 행의 큐 이름으로")
    void pump_sameProjectTwice_declaresOnce() {
        stubConfirms(Map.of());
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(
                new OutboxRow(1, PROJECT_ID, "GITHUB", "{}", 0),
                new OutboxRow(2, OTHER_PROJECT_ID, "JIRA", "{}", 0),
                new OutboxRow(3, PROJECT_ID, "SLACK", "{}", 0)));

        relay().pump();

        ArgumentCaptor<Queue> queues = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(2)).declareQueue(queues.capture());
        assertThat(queues.getAllValues()).extracting(Queue::getName).containsExactly(
                "history.project." + PROJECT_ID, "history.project." + OTHER_PROJECT_ID);
        ArgumentCaptor<String> routingKeys = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate, times(3)).send(eq(""), routingKeys.capture(), any(Message.class), any(CorrelationData.class));
        assertThat(routingKeys.getAllValues()).containsExactly(
                "history.project." + PROJECT_ID, "history.project." + OTHER_PROJECT_ID, "history.project." + PROJECT_ID);
    }

    @Test
    @DisplayName("메시지 — body는 payload UTF-8 바이트, JSON·UTF-8·PERSISTENT, 빈 exchange·큐 이름 routing key·correlation id가 맞다")
    void pump_message_hasWireFormat() {
        stubConfirms(Map.of());
        String payload = "{\"title\":\"한글 payload\"}";
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE))
                .thenReturn(List.of(new OutboxRow(42, PROJECT_ID, "GITHUB", payload, 0)));

        relay().pump();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq(""), eq("history.project." + PROJECT_ID), message.capture(), correlation.capture());
        assertThat(message.getValue().getBody()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getValue().getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(correlation.getValue().getId()).isEqualTo("42");
    }

    // ─── ② 정상 단계 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 단계 — 3건 전부 ack → markPublished([1,2,3]) 1회, 실패 기록·알림 없음")
    void pump_fresh_allAcked_marksAll() {
        stubConfirms(Map.of());
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));

        relay().pump();

        verifyMarkedOnce(List.of(1L, 2L, 3L));
        verifyNoRecordFailure();
        verifyNoInteractions(operatorAlert);
    }

    @Test
    @DisplayName("정상 단계 — 가운데 행 nack → 그 행만 recordFailure([2], 0), 앞뒤 행은 [1,3]을 한 번에 표시")
    void pump_fresh_middleNacked_recordsOnlyThatRowAndMarksOthers() {
        stubConfirms(1, Outcome.NACK);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));

        relay().pump();

        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verifyMarkedOnce(List.of(1L, 3L));
        verifyFailedOnce(List.of(2L), 0);
    }

    @Test
    @DisplayName("정상 단계 — 가운데 행 unroutable(ack + returned) → nack와 동일하게 그 행만 실패 기록")
    void pump_fresh_middleUnroutable_recordedLikeNack() {
        stubConfirms(1, Outcome.UNROUTABLE);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));

        relay().pump();

        verifyMarkedOnce(List.of(1L, 3L));
        verifyFailedOnce(List.of(2L), 0);
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 가운데 행 확인 응답 시간 초과 → 그 행은 표시도 실패 기록도 없고, 앞뒤 행만 표시")
    void pump_fresh_middleTimesOut_leavesRowUntouched() {
        stubConfirms(1, Outcome.PENDING);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));

        relay().pump();

        verifyMarkedOnce(List.of(1L, 3L));
        verifyNoRecordFailure();
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — nack와 시간 초과가 섞임 → nack 행만 기록, 미완료 행은 손대지 않고 나머지는 표시")
    void pump_fresh_nackAndTimeoutMixed_onlyNackRecorded() {
        stubConfirms(Map.of(1, Outcome.NACK, 2, Outcome.PENDING));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 4));

        relay().pump();

        verifyMarkedOnce(List.of(1L, 4L));
        verifyFailedOnce(List.of(2L), 0);
    }

    @Test
    @DisplayName("정상 단계 — 3번째 send가 AmqpException → 전송 중단, 앞서 ack 받은 [1,2]만 표시, 뒤 행은 기록도 표시도 없고 예외 비전파")
    void pump_fresh_sendThrows_marksAckedAndLeavesRest() {
        stubConfirms(2, Outcome.SEND_FAILS);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 4));
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();

        // 3번째에서 중단했으므로 4번째는 전송하지 않는다
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verifyMarkedOnce(List.of(1L, 2L));
        verifyNoRecordFailure();
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 큐 선언이 AmqpException → send 미호출, 표시·기록 없음, 예외 비전파")
    void pump_fresh_declareThrows_sendsNothing() {
        when(amqpAdmin.declareQueue(any(Queue.class)))
                .thenThrow(new AmqpConnectException(new java.net.ConnectException("x")));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();

        verifyNoInteractions(rabbitTemplate);
        verifyNoMarkPublished();
        verifyNoRecordFailure();
    }

    @Test
    @DisplayName("정상 단계 — nack가 나도 운영자 알림은 부르지 않는다")
    void pump_fresh_nack_doesNotAlert() {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 2));

        relay().pump();

        verifyNoInteractions(operatorAlert);
    }

    // 종료 조건이 깨지면 fetch 스텁이 마지막 값을 반복해 무한 루프가 되므로, 스위트가 멈추는 대신 실패하게 한다
    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 200건 전부 ack 후 50건 전부 ack → fetchFresh 2회(두 번째가 가득 차지 않아 종료)")
    void pump_fresh_fullBatchAllAcked_readsNextBatch() {
        stubConfirms(Map.of());
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE))
                .thenReturn(rows(1, 200))
                .thenReturn(rows(201, 50));

        relay().pump();

        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
        verify(outbox).markPublished(rows(1, 200).stream().map(OutboxRow::id).toList());
        verify(outbox).markPublished(rows(201, 50).stream().map(OutboxRow::id).toList());
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 200건 중 100번째 nack → 다음 묶음을 읽지 않는다(fetchFresh 1회)")
    void pump_fresh_nackInFullBatch_doesNotReadNextBatch() {
        stubConfirms(99, Outcome.NACK);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 200));

        relay().pump();

        verify(outbox, times(1)).fetchFresh(OutboxRelay.BATCH_SIZE);
        verifyFailedOnce(List.of(100L), 0);
    }

    // ─── ① 재시도 단계 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("재시도 단계 — ack → markPublished([id]), 실패 기록·알림 없음")
    void pump_retry_acked_marksRow() {
        stubConfirms(Map.of());
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 2)));

        relay().pump();

        verify(outbox).markPublished(List.of(7L));
        verifyNoRecordFailure();
        verifyNoInteractions(operatorAlert);
    }

    @ParameterizedTest(name = "읽은 attempts {0} → 간격 {1}초")
    @CsvSource({"1,5", "2,15", "3,30", "4,60", "5,600", "9,600"})
    @DisplayName("재시도 단계 — nack 시 기록 뒤 횟수(읽은 attempts + 1)로 간격을 정한다")
    void pump_retry_nack_delayFollowsAttemptsAfterRecording(int readAttempts, int expectedDelay) {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, readAttempts)));

        relay().pump();

        verifyFailedOnce(List.of(7L), expectedDelay);
        verify(outbox, never()).markPublished(List.of(7L));
    }

    @Test
    @DisplayName("재시도 단계 — unroutable(ack + returned)도 nack와 같이 기록한다")
    void pump_retry_unroutable_recordedLikeNack() {
        stubConfirms(0, Outcome.UNROUTABLE);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 2)));

        relay().pump();

        verifyFailedOnce(List.of(7L), 15);
        verify(outbox, never()).markPublished(List.of(7L));
    }

    @ParameterizedTest(name = "읽은 attempts {0} → 알림 {1}")
    @CsvSource({"1,false", "2,false", "3,false", "4,true", "5,true", "9,true"})
    @DisplayName("재시도 단계 — 기록 뒤 횟수가 5 이상일 때만 outbox_row_stuck 알림, 문구에 프로젝트 id·source·행 id 포함")
    void pump_retry_nack_alertsOnlyFromFifthFailure(int readAttempts, boolean alerted) {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE))
                .thenReturn(List.of(new OutboxRow(4242, PROJECT_ID, "JIRA", "{}", readAttempts)));

        relay().pump();

        if (alerted) {
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(operatorAlert).notify(eq("outbox_row_stuck"), text.capture());
            assertThat(text.getValue()).contains(PROJECT_ID.toString(), "JIRA", "4242");
        } else {
            verify(operatorAlert, never()).notify(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("재시도 단계 — 알림 문구에 기록 뒤 횟수(읽은 attempts + 1)가 들어간다")
    void pump_retry_alertText_containsAttemptsAfterRecording() {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE))
                .thenReturn(List.of(new OutboxRow(4242, PROJECT_ID, "JIRA", "{}", 6)));

        relay().pump();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(operatorAlert).notify(eq("outbox_row_stuck"), text.capture());
        assertThat(text.getValue()).contains("7");
    }

    @Test
    @DisplayName("재시도 단계 — nack 뒤에도 다음 재시도 행을 계속 처리한다")
    void pump_retry_nack_continuesWithNextRow() {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 1), retryRow(8, 1)));

        relay().pump();

        verify(rabbitTemplate, times(2)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(outbox).recordFailure(List.of(7L), 5);
        verify(outbox).markPublished(List.of(8L));
    }

    @Test
    @DisplayName("재시도 단계 — 행마다 그 행의 프로젝트 큐를 선언한 뒤 전송한다")
    void pump_retry_declaresQueuePerRowBeforeSending() {
        stubConfirms(Map.of());
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(
                new OutboxRow(7, PROJECT_ID, "GITHUB", "{}", 1),
                new OutboxRow(8, OTHER_PROJECT_ID, "GITHUB", "{}", 1)));

        relay().pump();

        InOrder order = inOrder(amqpAdmin, rabbitTemplate);
        order.verify(amqpAdmin).declareQueue(argThat((Queue q) -> q.getName().equals(queueOf(PROJECT_ID))));
        order.verify(rabbitTemplate).send(eq(""), eq(queueOf(PROJECT_ID)), any(Message.class), any(CorrelationData.class));
        order.verify(amqpAdmin).declareQueue(argThat((Queue q) -> q.getName().equals(queueOf(OTHER_PROJECT_ID))));
        order.verify(rabbitTemplate).send(eq(""), eq(queueOf(OTHER_PROJECT_ID)), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest(name = "읽은 attempts {0} → 간격 {1}초, 알림 {2}")
    @CsvSource({"1,5,false", "4,60,true"})
    @Timeout(10)
    @DisplayName("재시도 단계 — 시간 초과 → 그 행을 기록·알림하고 남은 재시도 행은 보내지 않으며, 정상 단계는 실행해 표시한다")
    void pump_retry_timeout_recordsRowSkipsRestRetriesButRunsFreshPhase(int readAttempts, int expectedDelay, boolean alerted) {
        stubConfirms(0, Outcome.PENDING);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE))
                .thenReturn(List.of(retryRow(7, readAttempts), retryRow(8, 1)));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 2));

        relay().pump();

        // 재시도 1건 + 정상 2건. 남은 재시도 행(8)은 전송되지 않는다
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(outbox).recordFailure(List.of(7L), expectedDelay);
        verify(outbox, never()).recordFailure(eq(List.of(8L)), anyInt());
        verify(outbox, never()).markPublished(List.of(8L));
        verifyFailedOnce(List.of(7L), expectedDelay);
        // 정상 단계는 실행된다
        verify(outbox).fetchFresh(OutboxRelay.BATCH_SIZE);
        verifyMarkedOnce(List.of(1L, 2L));
        if (alerted) {
            verify(operatorAlert).notify(eq("outbox_row_stuck"), anyString());
        } else {
            verify(operatorAlert, never()).notify(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("재시도 단계 — 큐 선언 실패 → 행을 기록하지 않고 남은 재시도·정상 단계 없이 틱 종료")
    void pump_retry_declareThrows_endsTickWithoutRecording() {
        when(amqpAdmin.declareQueue(any(Queue.class)))
                .thenThrow(new AmqpConnectException(new java.net.ConnectException("x")));
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 4), retryRow(8, 1)));
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();

        verifyNoRecordFailure();
        verifyNoMarkPublished();
        verifyNoInteractions(rabbitTemplate);
        verify(outbox, never()).fetchFresh(anyInt());
        verify(operatorAlert, never()).notify(anyString(), anyString());
    }

    @Test
    @DisplayName("재시도 단계 — 전송 예외 → 행을 기록하지 않고 남은 재시도·정상 단계 없이 틱 종료")
    void pump_retry_sendThrows_endsTickWithoutRecording() {
        stubConfirms(0, Outcome.SEND_FAILS);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 4), retryRow(8, 1)));
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();

        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verifyNoRecordFailure();
        verifyNoMarkPublished();
        verify(outbox, never()).fetchFresh(anyInt());
        verify(operatorAlert, never()).notify(anyString(), anyString());
    }

    // ─── 틱 간격(브로커 문제 틱) ─────────────────────────────────────────────────

    @Test
    @DisplayName("브로커 문제 틱이 이어지면 건너뛰는 시간이 1·2·4·8·16·30·30초로 늘어난다")
    void pump_consecutiveBrokerProblemTicks_backOffUpToThirtySeconds() {
        when(amqpAdmin.declareQueue(any(Queue.class)))
                .thenThrow(new AmqpConnectException(new java.net.ConnectException("x")));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();
        long[] skipSeconds = {1, 2, 4, 8, 16, 30, 30};

        for (int i = 0; i < skipSeconds.length; i++) {
            int ticksRun = i + 1;
            Duration skip = Duration.ofSeconds(skipSeconds[i]);

            relay.pump();
            verify(outbox, times(ticksRun)).fetchDueRetries(OutboxRelay.BATCH_SIZE);
            verify(outbox, times(ticksRun)).fetchFresh(OutboxRelay.BATCH_SIZE);

            clock.advance(skip.minus(ONE_MS));
            relay.pump();
            // 간격 − 1ms 시점에는 건너뛴다 — 저장소를 부르지 않는다
            verify(outbox, times(ticksRun)).fetchDueRetries(OutboxRelay.BATCH_SIZE);
            verify(outbox, times(ticksRun)).fetchFresh(OutboxRelay.BATCH_SIZE);

            clock.advance(ONE_MS);
            // 다음 반복의 첫 pump가 간격 경과 시점에 실행되는지 확인한다
        }
        relay.pump();
        verify(outbox, times(skipSeconds.length + 1)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("전송 예외로 끝난 틱도 브로커 문제 틱이다 — 1초 안에는 건너뛰고 1초 뒤에는 실행")
    void pump_sendException_isBrokerProblemTick() {
        stubConfirms(0, Outcome.SEND_FAILS);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();

        relay.pump();
        clock.advance(Duration.ofMillis(999));
        relay.pump();
        verify(outbox, times(1)).fetchFresh(OutboxRelay.BATCH_SIZE);

        clock.advance(ONE_MS);
        relay.pump();
        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계의 시간 초과로 끝난 틱도 브로커 문제 틱이다 — 1초 안에는 건너뛰고 1초 뒤에는 실행")
    void pump_freshPhaseTimeout_isBrokerProblemTick() {
        stubConfirms(0, Outcome.PENDING);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();

        relay.pump();
        clock.advance(Duration.ofMillis(999));
        relay.pump();
        verify(outbox, times(1)).fetchFresh(OutboxRelay.BATCH_SIZE);

        clock.advance(ONE_MS);
        relay.pump();
        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("브로커 문제 틱 뒤 깨끗한 틱이 한 번 있으면 연속 횟수가 0으로 돌아가 다음 문제 틱의 간격이 다시 1초다")
    void pump_cleanTick_resetsConsecutiveCount() {
        AmqpConnectException down = new AmqpConnectException(new java.net.ConnectException("x"));
        // 1·2번째 틱: 선언 실패, 3번째: 정상, 4번째: 선언 실패
        when(amqpAdmin.declareQueue(any(Queue.class)))
                .thenThrow(down).thenThrow(down).thenReturn("q").thenThrow(down);
        stubConfirms(Map.of());
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();

        relay.pump();                                  // 문제 틱 1 → 1초 건너뜀
        clock.advance(Duration.ofSeconds(1));
        relay.pump();                                  // 문제 틱 2 → 2초 건너뜀
        clock.advance(Duration.ofSeconds(2));
        relay.pump();                                  // 깨끗한 틱 → 연속 횟수 0
        relay.pump();                                  // 곧바로 실행(건너뛰지 않음), 문제 틱 1 → 1초 건너뜀
        verify(outbox, times(4)).fetchFresh(OutboxRelay.BATCH_SIZE);

        clock.advance(Duration.ofMillis(999));
        relay.pump();
        verify(outbox, times(4)).fetchFresh(OutboxRelay.BATCH_SIZE);

        clock.advance(ONE_MS);
        relay.pump();
        verify(outbox, times(5)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("정상 단계에서 nack만 있었던 틱은 브로커 문제 틱이 아니다 — 시계를 옮기지 않아도 다음 pump가 실행된다")
    void pump_freshNackOnly_isNotBrokerProblemTick() {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();

        relay.pump();
        relay.pump();

        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("재시도 단계에서 nack만 있었던 틱도 브로커 문제 틱이 아니다 — 시계를 옮기지 않아도 다음 pump가 실행된다")
    void pump_retryNackOnly_isNotBrokerProblemTick() {
        stubConfirms(0, Outcome.NACK);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 1)));
        OutboxRelay relay = relay();

        relay.pump();
        relay.pump();

        verify(outbox, times(2)).fetchDueRetries(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @Timeout(10)
    @DisplayName("재시도 단계만 시간 초과이고 정상 단계가 깨끗하면 브로커 문제 틱이 아니다 — 시계를 옮기지 않아도 다음 pump가 실행된다")
    void pump_retryTimeoutButFreshClean_isNotBrokerProblemTick() {
        stubConfirms(0, Outcome.PENDING);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 1)));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();

        relay.pump();
        relay.pump();

        verify(outbox, times(2)).fetchDueRetries(OutboxRelay.BATCH_SIZE);
        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    // ─── 확인 응답 대기 예외·스레드 중단 ─────────────────────────────────────────

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 가운데 행의 확인 응답 future가 예외로 끝남 → 그 행은 표시도 실패 기록도 없고, 앞뒤 행만 표시")
    void pump_fresh_middleFutureFails_leavesRowUntouched() {
        stubConfirms(1, Outcome.FUTURE_FAILS);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));

        relay().pump();

        verifyMarkedOnce(List.of(1L, 3L));
        verifyNoRecordFailure();
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계의 future 예외로 끝난 틱도 브로커 문제 틱이다 — 1초 안에는 건너뛰고 1초 뒤에는 실행")
    void pump_freshPhaseFutureFails_isBrokerProblemTick() {
        stubConfirms(1, Outcome.FUTURE_FAILS);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));
        OutboxRelay relay = relay();

        relay.pump();
        clock.advance(Duration.ofMillis(999));
        relay.pump();
        verify(outbox, times(1)).fetchFresh(OutboxRelay.BATCH_SIZE);

        clock.advance(ONE_MS);
        relay.pump();
        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @Timeout(10)
    @DisplayName("재시도 단계 — future가 예외로 끝남 → 시간 초과와 같이 그 행을 기록하고 남은 재시도 행은 보내지 않으며, 정상 단계는 실행한다")
    void pump_retry_futureFails_recordsRowSkipsRestRetriesButRunsFreshPhase() {
        stubConfirms(0, Outcome.FUTURE_FAILS);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 1), retryRow(8, 1)));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 2));

        relay().pump();

        // 재시도 1건 + 정상 2건. 남은 재시도 행(8)은 전송되지 않는다
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verifyFailedOnce(List.of(7L), 5);
        verify(outbox, never()).markPublished(List.of(8L));
        verify(outbox).fetchFresh(OutboxRelay.BATCH_SIZE);
        verifyMarkedOnce(List.of(1L, 2L));
    }

    @Test
    @Timeout(10)
    @DisplayName("재시도 단계 — 확인 대기 중 스레드 중단 → 행에 기록·표시·알림 없이, 남은 재시도·정상 단계 없이 틱을 끝내고 인터럽트 플래그를 유지")
    void pump_retry_interrupted_endsTickWithoutTouchingRows() {
        stubConfirms(0, Outcome.INTERRUPTS);
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(retryRow(7, 4), retryRow(8, 1)));
        OutboxRelay relay = relay();

        try {
            assertThatCode(relay::pump).doesNotThrowAnyException();

            boolean interruptFlag = Thread.currentThread().isInterrupted();
            Thread.interrupted();
            assertThat(interruptFlag).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verifyNoRecordFailure();
        verifyNoMarkPublished();
        verify(operatorAlert, never()).notify(anyString(), anyString());
        verify(outbox, never()).fetchFresh(anyInt());
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 행 2 확인 대기 중 중단 → 앞서 ack된 [1]만 표시, 중단된 행·뒤 행은 표시도 기록도 없고 다음 묶음을 읽지 않는다")
    void pump_fresh_interrupted_marksOnlyRowsAckedBeforeInterrupt() {
        stubConfirms(Map.of(1, Outcome.INTERRUPTS, 2, Outcome.ACK));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));
        OutboxRelay relay = relay();

        try {
            assertThatCode(relay::pump).doesNotThrowAnyException();

            boolean interruptFlag = Thread.currentThread().isInterrupted();
            Thread.interrupted();
            assertThat(interruptFlag).isTrue();
        } finally {
            Thread.interrupted();
        }

        verifyMarkedOnce(List.of(1L));
        verifyNoRecordFailure();
        verify(outbox, times(1)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @Timeout(10)
    @DisplayName("정상 단계 — 행 1 nack 뒤 행 2 확인 대기 중 중단 → 거부된 [1]은 recordFailure([1], 0)로 기록하고, 뒤 행 3은 ack여도 표시하지 않는다")
    void pump_fresh_interruptedAfterNack_recordsRejectedRow() {
        stubConfirms(Map.of(0, Outcome.NACK, 1, Outcome.INTERRUPTS, 2, Outcome.ACK));
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 3));
        OutboxRelay relay = relay();

        try {
            assertThatCode(relay::pump).doesNotThrowAnyException();

            boolean interruptFlag = Thread.currentThread().isInterrupted();
            Thread.interrupted();
            assertThat(interruptFlag).isTrue();
        } finally {
            Thread.interrupted();
        }

        verifyFailedOnce(List.of(1L), 0);
        verifyNoMarkPublished();
    }

    @Test
    @Timeout(10)
    @DisplayName("중단으로 끝난 틱은 브로커 문제 틱이 아니다 — 플래그를 지우면 시계를 옮기지 않아도 다음 pump가 실행된다")
    void pump_interrupted_isNotBrokerProblemTick() {
        stubConfirms(0, Outcome.INTERRUPTS);
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE)).thenReturn(rows(1, 1));
        OutboxRelay relay = relay();

        try {
            relay.pump();
            Thread.interrupted();
            relay.pump();
        } finally {
            Thread.interrupted();
        }

        verify(outbox, times(2)).fetchFresh(OutboxRelay.BATCH_SIZE);
    }

    // ─── pump 예외 격리 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("pump — fetchDueRetries의 DB 예외가 나도 전파하지 않고, 연속 횟수를 바꾸지 않아 다음 pump가 곧바로 실행된다")
    void pump_retryFetchThrows_isSwallowed() {
        when(outbox.fetchDueRetries(OutboxRelay.BATCH_SIZE))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(List.of());
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();
        relay.pump();

        verify(outbox, times(2)).fetchDueRetries(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("pump — fetchFresh의 DB 예외가 나도 전파하지 않는다(스케줄러 스레드 보호)")
    void pump_freshFetchThrows_isSwallowed() {
        when(outbox.fetchFresh(OutboxRelay.BATCH_SIZE))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        OutboxRelay relay = relay();

        assertThatCode(relay::pump).doesNotThrowAnyException();
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────────

    private OutboxRelay relay() {
        return new OutboxRelay(outbox, rabbitTemplate, amqpAdmin, operatorAlert, clock, CONFIRM_TIMEOUT_MS);
    }

    private static String queueOf(UUID projectId) {
        return "history.project." + projectId;
    }

    // id = firstId..firstId+count-1, source GITHUB, attempts 0
    private List<OutboxRow> rows(long firstId, int count) {
        return rows(firstId, count, 0);
    }

    private List<OutboxRow> rows(long firstId, int count, int attempts) {
        return IntStream.range(0, count)
                .mapToObj(i -> new OutboxRow(firstId + i, PROJECT_ID, "GITHUB", "{\"n\":" + (firstId + i) + "}", attempts))
                .collect(Collectors.toList());
    }

    private OutboxRow retryRow(long id, int attempts) {
        return new OutboxRow(id, PROJECT_ID, "GITHUB", "{\"n\":" + id + "}", attempts);
    }

    private static ArgumentMatcher<List<Long>> nonEmpty() {
        return ids -> !ids.isEmpty();
    }

    // "비어 있지 않은 목록으로 불리지 않음" — 구현이 빈 목록으로 부르는 경우와 구별되게 단언한다
    private void verifyNoMarkPublished() {
        verify(outbox, never()).markPublished(argThat(nonEmpty()));
    }

    private void verifyNoRecordFailure() {
        verify(outbox, never()).recordFailure(argThat(nonEmpty()), anyInt());
    }

    // 정확히 이 id 목록으로 한 번, 그 밖의 비어 있지 않은 목록 호출은 없음
    private void verifyMarkedOnce(List<Long> ids) {
        verify(outbox).markPublished(ids);
        verify(outbox, times(1)).markPublished(argThat(nonEmpty()));
    }

    private void verifyFailedOnce(List<Long> ids, int delaySeconds) {
        verify(outbox).recordFailure(ids, delaySeconds);
        verify(outbox, times(1)).recordFailure(argThat(nonEmpty()), anyInt());
    }

    // 실제 흐름을 그대로 타되, 시각만 테스트가 직접 이동시키는 Clock
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
