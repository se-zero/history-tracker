package com.history.pipeline_worker.config;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    // ai-engine이 소비하는 wire 포맷 보호: occurredAt(Instant)은 ISO-8601 문자열로 직렬화되어야 한다(숫자 timestamp 아님)
    @Test
    void messageConverter_serializesInstantAsIso8601String() {
        MessageConverter converter = new RabbitMqConfig().messageConverter();
        NormalizedEvent event = new NormalizedEvent(
                "11111111-1111-1111-1111-111111111111",
                "ChangeSet",
                "GITHUB",
                Instant.parse("2026-06-21T03:04:05Z"),
                new ActorDto("octocat", "The Octocat", null),
                Map.of("hash", "abc123"),
                Map.of()
        );

        Message message = converter.toMessage(event, new MessageProperties());
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        assertThat(body).contains("\"occurredAt\":\"2026-06-21T03:04:05");
    }
}
