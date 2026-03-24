package com.history.pipeline_worker.dto;

import java.time.Instant;
import java.util.Map;

public record NormalizedEvent(

        String nodeType,

        String source,

        Instant occurredAt,

        ActorDto actor,

        Map<String, Object> properties,

        // 텍스트에서 추출한 다른 시스템 참조
        // 예: { "jiraKey": "PAYMENT-301", "prNumber": "142" }
        Map<String, String> refs
) {}
