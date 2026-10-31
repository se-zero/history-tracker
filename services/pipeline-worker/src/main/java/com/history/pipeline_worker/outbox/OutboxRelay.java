package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.outbox.OutboxRepository.OutboxRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 아웃박스의 미발행 행을 RabbitMQ로 내보내는 릴레이. 행별 publisher confirm을 확인한 뒤에야 발행 완료로 표시한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    static final int BATCH_SIZE = 200;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKeyPrefix;
    private final long confirmTimeoutMs;

    public OutboxRelay(
            OutboxRepository outbox,
            RabbitTemplate rabbitTemplate,
            @Value("${app.rabbitmq.exchange}") String exchange,
            @Value("${app.rabbitmq.routing-key-prefix:event}") String routingKeyPrefix,
            @Value("${app.rabbitmq.publish-confirm-timeout-ms:10000}") long confirmTimeoutMs) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKeyPrefix = routingKeyPrefix;
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
        List<Pending> pending = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            CorrelationData correlation = new CorrelationData(String.valueOf(row.id()));
            try {
                rabbitTemplate.send(exchange, resolveRoutingKey(row.source()), toMessage(row), correlation);
            } catch (AmqpException e) {
                log.warn("아웃박스 행 전송 실패 — 이 지점에서 전송 중단: id={}, 원인={}", row.id(), e.toString());
                break;
            }
            pending.add(new Pending(row, correlation));
        }

        int prefix = confirmedPrefix(pending);

        // 접두사만 표시하는 이유: 프로젝트 안 이벤트 순서를 지키면서(실패 지점 이후는 다음 틱에 같은 순서로 재전송)
        // 중복 발행을 첫 실패 이후분으로 한정하기 위해서다. "보낸 뒤 표시"라 at-least-once이고,
        // 중복은 ai-engine의 MERGE가 흡수한다.
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

    /**
     * routing key를 source에서 유도한다 — {@code {prefix}.{소문자 source}}
     * (예: {@code GITHUB} → {@code event.github}, {@code GOOGLE_CHAT} → {@code event.google_chat}).
     *
     * <p>표기 규칙의 단일 출처는 `docs/normalized-event.md`의 「source · 표기 규칙」이다.
     * 큐 바인딩이 {@code event.#}라 <b>새 소스는 브로커 설정도 이 클래스도 고치지 않고</b> 라우팅된다 —
     * provider별 분기를 두면 커넥터마다 이 공용 파일을 고쳐야 한다.</p>
     */
    private String resolveRoutingKey(String source) {
        if (source == null || source.isBlank()) {
            log.warn("source 없는 이벤트 — '{}.unknown'으로 발행", routingKeyPrefix);
            return routingKeyPrefix + ".unknown";
        }
        // Locale.ROOT — 터키어 로케일에서 'I'가 점 없는 'ı'로 내려가 라우팅 키가 어긋나는 것을 막는다
        return routingKeyPrefix + "." + source.trim().toLowerCase(Locale.ROOT);
    }

    private record Pending(OutboxRow row, CorrelationData correlation) {}
}
