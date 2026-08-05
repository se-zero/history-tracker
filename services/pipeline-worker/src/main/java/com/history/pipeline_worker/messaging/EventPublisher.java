package com.history.pipeline_worker.messaging;

import com.history.pipeline_worker.dto.NormalizedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKeyPrefix;
    private final long confirmTimeoutMs;

    public EventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${app.rabbitmq.exchange}") String exchange,
            @Value("${app.rabbitmq.routing-key-prefix:event}") String routingKeyPrefix,
            @Value("${app.rabbitmq.publish-confirm-timeout-ms:10000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKeyPrefix = routingKeyPrefix;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /**
     * 이벤트 1건 = 메시지 1개로 발행하고 publisher confirm으로 브로커 수신을 검증한다.
     * 한 건이라도 nack·라우팅 실패(unroutable)·타임아웃이면 {@link EventPublishException}을 던진다 —
     * 호출부가 checkpoint를 전진시키지 못하게 해 유실(영구 빈칸)을 막고 다음 수집에서 재발행되게 한다.
     * 먼저 일괄 전송한 뒤 confirm을 모아 대기하므로, 건건 동기 대기로 인한 지연이 없다.
     * 전부 confirm되면 발행 건수를 반환한다.
     */
    public int publishAll(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) return 0;

        List<Pending> pending = new ArrayList<>(events.size());
        for (NormalizedEvent event : events) {
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.convertAndSend(exchange, resolveRoutingKey(event.source()), event, correlation);
            pending.add(new Pending(event, correlation));
        }

        awaitConfirms(pending);

        log.info("RabbitMQ 발행 확인 완료: {}건 → exchange '{}'", events.size(), exchange);
        return events.size();
    }

    // 전송한 모든 메시지의 confirm을 deadline 안에서 대기. nack·unroutable·timeout이면 예외.
    private void awaitConfirms(List<Pending> pending) {
        long deadline = System.currentTimeMillis() + confirmTimeoutMs;
        for (Pending p : pending) {
            CorrelationData.Confirm confirm = waitForConfirm(p, deadline);
            if (confirm == null || !confirm.ack()) {
                throw new EventPublishException("브로커가 메시지를 ack하지 않음: " + describe(p) + reason(confirm));
            }
            // mandatory 발행이라 라우팅 실패 시 ack=true와 함께 returned가 채워진다 — 별도 확인 필요
            if (p.correlation().getReturned() != null) {
                throw new EventPublishException("라우팅 실패(unroutable): " + describe(p)
                        + ", routingKey=" + p.correlation().getReturned().getRoutingKey());
            }
        }
    }

    private CorrelationData.Confirm waitForConfirm(Pending p, long deadline) {
        long remaining = Math.max(0, deadline - System.currentTimeMillis());
        try {
            return p.correlation().getFuture().get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EventPublishException("발행 confirm 타임아웃(" + confirmTimeoutMs + "ms): " + describe(p), e);
        } catch (ExecutionException e) {
            throw new EventPublishException("발행 confirm 대기 실패: " + describe(p), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("발행 confirm 대기 중단: " + describe(p), e);
        }
    }

    private String describe(Pending p) {
        NormalizedEvent e = p.event();
        return "source=" + e.source() + ", nodeType=" + e.nodeType() + ", occurredAt=" + e.occurredAt();
    }

    private String reason(CorrelationData.Confirm confirm) {
        return confirm != null && confirm.reason() != null ? ", reason=" + confirm.reason() : "";
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

    private record Pending(NormalizedEvent event, CorrelationData correlation) {}
}
