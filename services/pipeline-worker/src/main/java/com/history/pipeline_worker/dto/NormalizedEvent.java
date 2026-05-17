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
        // 예: { "jiraKey": "PAYMENT-301", "jiraKeys": ["PAYMENT-301", "PAYMENT-302"], "prNumber": "142" }
        // 값 타입이 String / List<String> / 그 외(parent key 등 정적 String) 혼합되므로 Object로 둔다.
        Map<String, Object> refs
) {}
