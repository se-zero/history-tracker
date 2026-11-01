package com.history.pipeline_worker.outbox;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 아웃박스의 미발행 행을 RabbitMQ로 내보내는 릴레이. 행별 publisher confirm을 확인한 뒤에야 발행 완료로 표시한다.
 *
 * <p>발행 대상은 프로젝트별 큐 {@code history.project.<projectId>}이고, 기본 익스체인지(이름 {@code ""})에
 * 큐 이름을 routing key로 바로 보낸다(기본 익스체인지는 모든 큐가 자기 이름으로 묶여 있어 바인딩 선언이 필요 없다).
 * 큐는 배치마다 선언한다(멱등) — 캐시하면 ai-engine이 프로젝트 삭제 때 큐를 지운 뒤 stale 캐시로 유실이 난다.
 * 큐에는 {@code x-single-active-consumer}를 걸어 한 프로젝트의 메시지가 한 소비자에게만 순서대로 가게 한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    static final int BATCH_SIZE = 200;
    static final String QUEUE_PREFIX = "history.project.";

    static String queueName(UUID projectId) {
        return QUEUE_PREFIX + projectId;
    }

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final long confirmTimeoutMs;

    public OutboxRelay(
            OutboxRepository outbox,
            RabbitTemplate rabbitTemplate,
            AmqpAdmin amqpAdmin,
            @Value("${app.rabbitmq.publish-confirm-timeout-ms:10000}") long confirmTimeoutMs) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    // fixedDelay는 직전 실행이 끝난 뒤 1초라 실행이 겹치지 않는다.
    // 예외가 스케줄러 스레드까지 올라가면 이후 틱이 멈추므로 여기서 전부 삼키고 다음 틱에 재시도한다.
    @Scheduled(fixedDelay = 1000)
    public void pump() {
        try {
            drain();
        } catch (RuntimeException e) {
            log.warn("아웃박스 릴레이 실행 실패 — 다음 주기에 재시도: {}", e.toString());
        }
    }

    int drain() {
        int total = 0;
        while (true) {
            List<OutboxRow> rows = outbox.fetchUnpublished(BATCH_SIZE);
            if (rows.isEmpty()) break;
            int published = publishBatch(rows);
            total += published;
            // 배치가 가득 찼고 전부 성공했을 때만 곧바로 다음 배치로 백로그를 소진한다.
            // 일부라도 실패했으면 같은 행을 바로 다시 집어 봐야 또 실패하므로 다음 틱에 재시도한다.
            if (published < rows.size() || rows.size() < BATCH_SIZE) break;
        }
        return total;
    }

    int publishBatch(List<OutboxRow> rows) {
        // 선언이 하나라도 실패하면 전송 자체를 하지 않는다 — 큐 없이 보내면 unroutable로 버려진다.
        for (UUID projectId : rows.stream().map(OutboxRow::projectId).distinct().toList()) {
            try {
                amqpAdmin.declareQueue(QueueBuilder.durable(queueName(projectId)).singleActiveConsumer().build());
            } catch (AmqpException e) {
                log.warn("프로젝트 큐 선언 실패 — 이 배치는 다음 주기에 재시도: queue={}, 원인={}", queueName(projectId), e.toString());
                return 0;
            }
        }

        List<Pending> pending = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            CorrelationData correlation = new CorrelationData(String.valueOf(row.id()));
            try {
                rabbitTemplate.send("", queueName(row.projectId()), toMessage(row), correlation);
            } catch (AmqpException e) {
                log.warn("아웃박스 행 전송 실패 — 이 지점에서 전송 중단: id={}, 원인={}", row.id(), e.toString());
                break;
            }
            pending.add(new Pending(row, correlation));
        }

        int prefix = confirmedPrefix(pending);

        // 접두사만 표시하는 이유: 같은 수집 흐름(한 provider의 한 번의 수집) 안 이벤트 순서를 지키면서(실패 지점 이후는 다음 틱에 같은 순서로 재전송)
        // 중복 발행을 첫 실패 이후분으로 한정하기 위해서다. id는 INSERT 시점의 번호라 커밋 순서와 다를 수 있어, 한 프로젝트에서
        // provider가 병렬로 수집될 때 provider 사이의 순서는 보장하지 않는다(순서가 필요한 관계는 전부 같은 provider 안에 있다).
        // "보낸 뒤 표시"라 at-least-once이고, 중복은 ai-engine의 MERGE가 흡수한다.
        if (prefix > 0) {
            List<Long> ids = new ArrayList<>(prefix);
            for (int i = 0; i < prefix; i++) ids.add(pending.get(i).row().id());
            outbox.markPublished(ids);
        }
        return prefix;
    }

    // 하나의 deadline 안에서 id 순서대로 confirm을 확인하고, 첫 실패의 인덱스(전부 성공이면 size)를 돌려준다.
    private int confirmedPrefix(List<Pending> pending) {
        long deadline = System.currentTimeMillis() + confirmTimeoutMs;
        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            String failure = failureOf(p, deadline);
            if (failure != null) {
                log.warn("아웃박스 발행 확인 실패 — 이후 행은 다음 주기에 재전송: id={}, source={}, {}",
                        p.row().id(), p.row().source(), failure);
                return i;
            }
        }
        return pending.size();
    }

    // 실패 사유 문자열, 성공이면 null
    private String failureOf(Pending p, long deadline) {
        long remaining = Math.max(0, deadline - System.currentTimeMillis());
        CorrelationData.Confirm confirm;
        try {
            confirm = p.correlation().getFuture().get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "발행 confirm 타임아웃(" + confirmTimeoutMs + "ms)";
        } catch (ExecutionException e) {
            return "발행 confirm 대기 실패: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "발행 confirm 대기 중단";
        }
        if (confirm == null || !confirm.ack()) {
            return "브로커가 메시지를 ack하지 않음" + reason(confirm);
        }
        // mandatory 발행이라 라우팅 실패 시 ack=true와 함께 returned가 채워진다 — 별도 확인 필요
        if (p.correlation().getReturned() != null) {
            // routingKey가 곧 큐 이름이다 — 선언과 발행 사이에 ai-engine이 큐를 지운 희귀 경우이며, 다음 틱에 재선언·재전송한다.
            return "라우팅 실패(unroutable): routingKey=" + p.correlation().getReturned().getRoutingKey();
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
}
