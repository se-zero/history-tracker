package com.history.pipeline_worker.messaging;

import com.history.pipeline_worker.collection.CollectionRunContext;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.outbox.OutboxRepository;
import com.history.pipeline_worker.outbox.OutboxRepository.OutboxInsert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class EventPublisher {

    private final OutboxRepository outbox;
    private final CollectionRunContext runContext;
    private final MessageConverter messageConverter;
    private final TransactionTemplate transactionTemplate;

    public EventPublisher(
            OutboxRepository outbox,
            CollectionRunContext runContext,
            MessageConverter messageConverter,
            TransactionTemplate transactionTemplate) {
        this.outbox = outbox;
        this.runContext = runContext;
        this.messageConverter = messageConverter;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 이벤트를 브로커로 보내지 않고 아웃박스 표에 한 트랜잭션으로 적재한다 (이벤트 1건 = 행 1개).
     * 브로커 발행과 publisher confirm 검증은 릴레이({@code outbox.OutboxRelay})가 맡는다.
     * 적재 중 DB 예외({@link org.springframework.dao.DataAccessException})는 그대로 전파된다 —
     * 호출부가 checkpoint를 전진시키지 못하게 해 유실(영구 빈칸)을 막고 다음 수집에서 다시 적재되게 한다.
     * checkpoint는 이 트랜잭션에 넣지 않는다 — 커서를 언제 전진시킬지는 커넥터가 소유한다.
     * 전부 적재되면 적재 건수를 반환한다.
     *
     * <p>수집 범위({@link CollectionRunContext})가 열려 있으면 적재하기 전에 연동이 아직 있는지 확인해
     * (해제됐으면 {@link com.history.pipeline_worker.collection.IntegrationDisconnectedException}, 한 건도 적재하지 않음)
     * 모든 이벤트에 그 연동 id를 싣는다 — 확인 이후에 해제돼 이미 적재된 이벤트는 ai-engine이 id로 버린다.</p>
     */
    public int publishAll(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) return 0;

        // 트랜잭션을 열기 전에 확인해, 해제된 연동이면 DB 커넥션을 잡지 않고 끝낸다
        runContext.ensureActive();
        Optional<String> integrationId = runContext.currentIntegrationId();

        List<OutboxInsert> rows = new ArrayList<>(events.size());
        for (NormalizedEvent original : events) {
            NormalizedEvent event = integrationId.map(original::withIntegrationId).orElse(original);
            // convertAndSend가 내부에서 쓰던 바로 그 변환기라 바이트가 기존과 동일하고, RabbitMqConfigTest가 그 포맷을 고정한다
            byte[] body = messageConverter.toMessage(event, new MessageProperties()).getBody();
            rows.add(new OutboxInsert(
                    UUID.fromString(event.projectId()),
                    integrationId.map(UUID::fromString).orElse(null),
                    // 빈 문자열은 릴레이가 event.unknown으로 보낸다(기존 동작 유지)
                    event.source() == null || event.source().isBlank() ? "" : event.source(),
                    new String(body, StandardCharsets.UTF_8)));
        }

        transactionTemplate.executeWithoutResult(status -> outbox.insertAll(rows));

        log.info("아웃박스 적재: {}건", rows.size());
        return rows.size();
    }
}
