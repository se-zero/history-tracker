package com.history.pipeline_worker.messaging;

import com.history.pipeline_worker.dto.NormalizedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String githubKey;
    private final String jiraKey;
    private final String slackKey;

    public EventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${app.rabbitmq.exchange}") String exchange,
            @Value("${app.rabbitmq.routing-key-github}") String githubKey,
            @Value("${app.rabbitmq.routing-key-jira}") String jiraKey,
            @Value("${app.rabbitmq.routing-key-slack}") String slackKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.githubKey = githubKey;
        this.jiraKey = jiraKey;
        this.slackKey = slackKey;
    }

    /** 이벤트 1건 = 메시지 1개로 RabbitMQ에 발행 */
    public int publishAll(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) return 0;

        for (NormalizedEvent event : events) {
            rabbitTemplate.convertAndSend(exchange, resolveRoutingKey(event.source()), event);
        }
        log.info("RabbitMQ 발행 완료: {}건 → exchange '{}'", events.size(), exchange);
        return events.size();
    }

    private String resolveRoutingKey(String source) {
        return switch (source.toUpperCase()) {
            case "GITHUB" -> githubKey;
            case "JIRA"   -> jiraKey;
            case "SLACK"  -> slackKey;
            default -> {
                log.warn("알 수 없는 source '{}', 기본 라우팅 키 사용", source);
                yield "event.unknown";
            }
        };
    }
}
