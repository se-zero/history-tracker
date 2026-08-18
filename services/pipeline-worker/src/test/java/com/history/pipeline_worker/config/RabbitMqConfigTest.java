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

    // ai-engine 소비자 측(이미 구현·green)과 합의된 필드명 "bot"을 wire 포맷으로 고정한다.

    @Test
    void messageConverter_serializesActorBotField_whenPresent() {
        MessageConverter converter = new RabbitMqConfig().messageConverter();
        NormalizedEvent event = new NormalizedEvent(
                "11111111-1111-1111-1111-111111111111",
                "Issue",
                "LINEAR",
                Instant.parse("2026-06-21T03:04:05Z"),
                new ActorDto("bot-1", "GitHub Sync", null, true),
                Map.of("external_id", "abc"),
                Map.of()
        );

        Message message = converter.toMessage(event, new MessageProperties());
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        assertThat(body).contains("\"bot\":true");
    }

    // 이 프로젝트의 ObjectMapper/메시지 컨버터 설정에는 @JsonInclude(NON_NULL) 등 null 배제
    // 설정이 없다(id/name/email 등 기존 ActorDto 필드도 null이면 리터럴 null로 나간다) — bot 필드도
    // 그 기존 관례를 따른다: 생략도, false로의 강제 변환도 하지 않는다.
    @Test
    void messageConverter_serializesNullActorBot_asJsonNull() {
        MessageConverter converter = new RabbitMqConfig().messageConverter();
        NormalizedEvent event = new NormalizedEvent(
                "11111111-1111-1111-1111-111111111111",
                "Issue",
                "LINEAR",
                Instant.parse("2026-06-21T03:04:05Z"),
                new ActorDto("user-1", "Jane Doe", "jane@example.com", null),
                Map.of("external_id", "abc"),
                Map.of()
        );

        Message message = converter.toMessage(event, new MessageProperties());
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        assertThat(body).contains("\"bot\":null");
    }
}
