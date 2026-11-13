package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.alert.OperatorAlert;
import com.history.pipeline_worker.outbox.OutboxRepository.OutboxRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 아웃박스의 미발행 행을 RabbitMQ로 내보내는 릴레이. 행별 publisher confirm을 확인한 뒤에야 발행 완료로 표시한다.
 *
 * <p>한 틱은 두 단계다. ① 재시도 단계는 발행에 실패해 기록된 행(attempts &gt; 0) 중 다음 시도 시각이 지난 것을
 * 한 건씩 보낸다. ② 정상 단계는 한 번도 시도하지 않은 행을 묶음으로 보낸다. 실패한 행만 정상 흐름에서 빠져 혼자 재시도되므로
 * 브로커가 계속 거부하는 행 하나가 다른 프로젝트의 전달을 막지 않는다.</p>
 *
 * <p>발행 대상은 프로젝트별 큐 {@code history.project.<projectId>}이고, 기본 익스체인지(이름 {@code ""})에
 * 큐 이름을 routing key로 바로 보낸다(기본 익스체인지는 모든 큐가 자기 이름으로 묶여 있어 바인딩 선언이 필요 없다).
 * 큐는 묶음·재시도 행마다 선언한다(멱등) — 캐시하면 ai-engine이 프로젝트 삭제 때 큐를 지운 뒤 stale 캐시로 유실이 난다.
 * 큐에는 {@code x-single-active-consumer}를 걸어 한 프로젝트의 메시지가 한 소비자에게만 순서대로 가게 한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    static final int BATCH_SIZE = 200;
    static final String QUEUE_PREFIX = "history.project.";

    // 기록 뒤 횟수가 이 값 이상이면 운영자에게 알린다
    private static final int ALERT_ATTEMPTS = 5;
    // 브로커 문제 틱이 연속 n번째일 때 건너뛰는 초(마지막 값을 반복)
    private static final long[] SKIP_SECONDS = {1, 2, 4, 8, 16, 30};

    static String queueName(UUID projectId) {
        return QUEUE_PREFIX + projectId;
    }

    // 기록 뒤 횟수(읽은 attempts + 1) 기준 재시도 간격(초)
    private static int retryDelaySeconds(int attemptsAfter) {
        return switch (attemptsAfter) {
            case 0, 1, 2 -> 5;
            case 3 -> 15;
            case 4 -> 30;
            case 5 -> 60;
            default -> 600;
        };
    }

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final OperatorAlert operatorAlert;
    private final Clock clock;
    private final long confirmTimeoutMs;

    private int brokerProblemTicks;
    private Instant skipUntil = Instant.MIN;

    public OutboxRelay(
            OutboxRepository outbox,
            RabbitTemplate rabbitTemplate,
            AmqpAdmin amqpAdmin,
            OperatorAlert operatorAlert,
            Clock clock,
            @Value("${app.rabbitmq.publish-confirm-timeout-ms:10000}") long confirmTimeoutMs) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.operatorAlert = operatorAlert;
        this.clock = clock;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    // fixedDelay는 직전 실행이 끝난 뒤 1초라 실행이 겹치지 않는다.
    // 예외가 스케줄러 스레드까지 올라가면 이후 틱이 멈추므로 여기서 전부 삼키고 다음 틱에 재시도한다.
    @Scheduled(fixedDelay = 1000)
    public void pump() {
        if (clock.instant().isBefore(skipUntil)) return;
        try {
            boolean brokerProblem = runTick() == Tick.BROKER_PROBLEM;
            // 브로커가 내려가 있으면 매초 선언·전송을 두드려 봐야 로그만 쌓이므로 연속될수록 틱을 건너뛴다
            brokerProblemTicks = brokerProblem ? brokerProblemTicks + 1 : 0;
            skipUntil = brokerProblem
                    ? clock.instant().plusSeconds(SKIP_SECONDS[Math.min(brokerProblemTicks, SKIP_SECONDS.length) - 1])
                    : Instant.MIN;
        } catch (RuntimeException e) {
            log.warn("아웃박스 릴레이 실행 실패 — 다음 주기에 재시도: {}", e.toString());
        }
    }

    private Tick runTick() {
        Tick retried = retryDueRows();
        if (retried != Tick.NORMAL) return retried;
        return publishFreshRows();
    }

    // 큐 선언·전송 예외로 틱을 끝내야 하면 BROKER_PROBLEM. 시간 초과는 그 행을 기록하고 남은 재시도만 멈춘다.
    // 중단(INTERRUPTED)이면 정상 단계까지 건너뛰고 틱을 끝낸다.
    private Tick retryDueRows() {
        for (OutboxRow row : outbox.fetchDueRetries(BATCH_SIZE)) {
            if (!declareQueue(row.projectId())) return Tick.BROKER_PROBLEM;
            CorrelationData correlation = new CorrelationData(String.valueOf(row.id()));
            try {
                rabbitTemplate.send("", queueName(row.projectId()), toMessage(row), correlation);
            } catch (AmqpException e) {
                log.warn("아웃박스 재시도 행 전송 실패 — 이번 틱 종료: id={}, 원인={}", row.id(), e.toString());
                return Tick.BROKER_PROBLEM;
            }

            Failure failure = failureOf(correlation, System.currentTimeMillis() + confirmTimeoutMs);
            if (failure != null && failure.kind() == FailureKind.INTERRUPTED) return Tick.INTERRUPTED;
            if (failure == null) {
                outbox.markPublished(List.of(row.id()));
                continue;
            }
            int attemptsAfter = row.attempts() + 1;
            outbox.recordFailure(List.of(row.id()), retryDelaySeconds(attemptsAfter));
            log.warn("아웃박스 재시도 행 발행 실패: id={}, source={}, attempts={}, {}",
                    row.id(), row.source(), attemptsAfter, failure.reason());
            if (attemptsAfter >= ALERT_ATTEMPTS) {
                operatorAlert.notify("outbox_row_stuck", "아웃박스 행이 계속 발행에 실패합니다: project=" + row.projectId()
                        + ", source=" + row.source() + ", id=" + row.id() + ", attempts=" + attemptsAfter
                        + ", 사유=" + failure.reason());
            }
            if (failure.kind() == FailureKind.NO_RESPONSE) break;
        }
        return Tick.NORMAL;
    }

    private Tick publishFreshRows() {
        boolean brokerProblem = false;
        while (true) {
            List<OutboxRow> rows = outbox.fetchFresh(BATCH_SIZE);
            if (rows.isEmpty()) break;
            BatchResult result = publishBatch(rows);
            if (result.interrupted()) return Tick.INTERRUPTED;
            brokerProblem |= result.brokerProblem();
            // 묶음이 가득 찼고 전부 성공했을 때만 곧바로 다음 묶음으로 백로그를 소진한다.
            // 일부라도 실패했으면 같은 행을 바로 다시 집어 봐야 또 실패하므로 다음 틱에 맡긴다.
            if (!result.allAcked() || rows.size() < BATCH_SIZE) break;
        }
        return brokerProblem ? Tick.BROKER_PROBLEM : Tick.NORMAL;
    }

    private boolean declareQueue(UUID projectId) {
        try {
            amqpAdmin.declareQueue(QueueBuilder.durable(queueName(projectId)).singleActiveConsumer().build());
            return true;
        } catch (AmqpException e) {
            log.warn("프로젝트 큐 선언 실패 — 다음 주기에 재시도: queue={}, 원인={}", queueName(projectId), e.toString());
            return false;
        }
    }

    // ack 받은 행은 위치와 무관하게 발행 완료로 표시하고, 거부된 행만 실패로 기록해 정상 흐름에서 뺀다.
    // 묶음에서의 거부는 다른 행 때문에 채널이 닫혀 덩달아 온 것일 수 있어, 묶음 안에서 다시 보내 봐야 원인이 가려진다.
    // 그래서 거부 행은 혼자 재시도 단계에서 보낸다(간격 0이라 다음 틱에 곧바로 대상이 된다).
    // 시간 초과(응답 없음)는 릴레이가 원인을 알 수 없다. 묶음에서는 어느 행 탓인지 가릴 수 없고 대개 브로커 쪽 문제
    // (브로커가 자원 부족으로 보내는 쪽을 막은 경우 등)라 세지 않고 다음 틱의 정상 단계가 다시 읽게 둔다.
    // 반대로 혼자 보냈는데도 답이 없는 행을 세지 않으면 그 행이 매 틱 대기 시간을 차지하므로 재시도 단계에서는 센다.
    // 남는 순서 위험: 혼자 보내도 실패하는 행은 같은 소스의 뒤 행보다 늦게 나간다.
    // 종료 중 스레드 중단은 행이나 브로커의 문제가 아니므로 기록하지 않고 틱을 끝낸다 —
    // 중단 전에 확인된 행(ack·거부)만 반영하고, 중단된 행과 그 뒤 행은 손대지 않아 다음 틱이 다시 읽는다.
    // "보낸 뒤 표시"라 at-least-once이고, 중복은 ai-engine의 MERGE가 흡수한다.
    private BatchResult publishBatch(List<OutboxRow> rows) {
        // 선언이 하나라도 실패하면 전송 자체를 하지 않는다 — 큐 없이 보내면 unroutable로 버려진다.
        for (UUID projectId : rows.stream().map(OutboxRow::projectId).distinct().toList()) {
            if (!declareQueue(projectId)) return new BatchResult(false, true, false);
        }

        List<Pending> pending = new ArrayList<>(rows.size());
        boolean sendFailed = false;
        for (OutboxRow row : rows) {
            CorrelationData correlation = new CorrelationData(String.valueOf(row.id()));
            try {
                rabbitTemplate.send("", queueName(row.projectId()), toMessage(row), correlation);
            } catch (AmqpException e) {
                log.warn("아웃박스 행 전송 실패 — 이 지점에서 전송 중단: id={}, 원인={}", row.id(), e.toString());
                sendFailed = true;
                break;
            }
            pending.add(new Pending(row, correlation));
        }

        long deadline = System.currentTimeMillis() + confirmTimeoutMs;
        List<Long> acked = new ArrayList<>();
        List<Long> rejected = new ArrayList<>();
        int noResponse = 0;
        boolean interrupted = false;
        String firstRejected = null;
        String firstNoResponse = null;
        for (Pending p : pending) {
            Failure failure = failureOf(p.correlation(), deadline);
            long id = p.row().id();
            if (failure != null && failure.kind() == FailureKind.INTERRUPTED) {
                interrupted = true;
                break;
            }
            if (failure == null) {
                acked.add(id);
            } else if (failure.kind() == FailureKind.REJECTED) {
                if (rejected.isEmpty()) firstRejected = "id=" + id + ", " + failure.reason();
                rejected.add(id);
            } else {
                if (noResponse == 0) firstNoResponse = "id=" + id + ", " + failure.reason();
                noResponse++;
            }
        }
        if (!rejected.isEmpty()) {
            log.warn("아웃박스 발행 거부 {}건 — 재시도 단계로 넘김: 첫 {}", rejected.size(), firstRejected);
        }
        if (noResponse > 0) {
            log.warn("아웃박스 발행 확인 응답 없음 {}건 — 기록 없이 다음 주기에 재전송: 첫 {}", noResponse, firstNoResponse);
        }

        outbox.markPublished(acked);
        outbox.recordFailure(rejected, 0);
        return new BatchResult(acked.size() == rows.size(), sendFailed || noResponse > 0, interrupted);
    }

    // 실패 정보, 성공이면 null
    private Failure failureOf(CorrelationData correlation, long deadline) {
        long remaining = Math.max(0, deadline - System.currentTimeMillis());
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Failure(FailureKind.NO_RESPONSE, "발행 confirm 타임아웃(" + confirmTimeoutMs + "ms)");
        } catch (ExecutionException e) {
            return new Failure(FailureKind.NO_RESPONSE, "발행 confirm 대기 실패: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure(FailureKind.INTERRUPTED, "발행 confirm 대기 중단");
        }
        if (confirm == null || !confirm.ack()) {
            return new Failure(FailureKind.REJECTED, "브로커가 메시지를 ack하지 않음" + reason(confirm));
        }
        // mandatory 발행이라 라우팅 실패 시 ack=true와 함께 returned가 채워진다 — 별도 확인 필요
        if (correlation.getReturned() != null) {
            // routingKey가 곧 큐 이름이다 — 선언과 발행 사이에 ai-engine이 큐를 지운 희귀 경우이며, 재선언·재전송으로 복구된다.
            return new Failure(FailureKind.REJECTED,
                    "라우팅 실패(unroutable): routingKey=" + correlation.getReturned().getRoutingKey());
        }
        return null;
    }

    private String reason(CorrelationData.Confirm confirm) {
        return confirm != null && confirm.reason() != null ? ", reason=" + confirm.reason() : "";
    }

    // __TypeId__ 헤더는 붙지 않지만 ai-engine은 json.loads(body)만 쓰므로 문제없다.
    private Message toMessage(OutboxRow row) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding("UTF-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), props);
    }

    private record Pending(OutboxRow row, CorrelationData correlation) {}

    // REJECTED: 브로커가 명시적으로 거부(nack·unroutable). NO_RESPONSE: 시간 초과 등 응답을 받지 못함.
    // INTERRUPTED: 대기 중 스레드 중단 — 행의 실패가 아니다
    private enum FailureKind { REJECTED, NO_RESPONSE, INTERRUPTED }

    private enum Tick { NORMAL, BROKER_PROBLEM, INTERRUPTED }

    private record Failure(FailureKind kind, String reason) {}

    private record BatchResult(boolean allAcked, boolean brokerProblem, boolean interrupted) {}
}
