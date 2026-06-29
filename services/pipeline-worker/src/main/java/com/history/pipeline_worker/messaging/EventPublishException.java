package com.history.pipeline_worker.messaging;

/**
 * RabbitMQ 발행이 브로커 confirm으로 검증되지 않았을 때 던진다.
 * 호출부(PipelineService)가 checkpoint를 전진시키지 못하게 막아, 조용한 유실(영구 빈칸)을 방지한다.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
