package com.history.pipeline_worker.dto;

import java.time.Instant;
import java.util.Map;

public record NormalizedEvent(

        // 이벤트가 속한 프로젝트 UUID — ai-engine이 Neo4j 노드를 프로젝트 단위로 격리하는 기준
        String projectId,

        String nodeType,

        String source,

        Instant occurredAt,

        ActorDto actor,

        Map<String, Object> properties,

        // 텍스트에서 추출한 다른 시스템 참조
        // 예: { "issueKey": "PAYMENT-301", "issueKeys": ["PAYMENT-301", "PAYMENT-302"], "prNumber": "142" }
        // 값 타입이 String / List<String> / 그 외(parent key 등 정적 String) 혼합되므로 Object로 둔다.
        Map<String, Object> refs,

        // 이 이벤트를 수집한 실행의 integrations.id. ai-engine은 이미 해제된 연동의 이벤트를 이 값으로 버린다.
        // 수집기(normalizer)는 채우지 않고 EventPublisher가 발행 직전에 붙인다 — 범위 밖 발행이면 null.
        String integrationId
) {

    public NormalizedEvent(
            String projectId,
            String nodeType,
            String source,
            Instant occurredAt,
            ActorDto actor,
            Map<String, Object> properties,
            Map<String, Object> refs
    ) {
        this(projectId, nodeType, source, occurredAt, actor, properties, refs, null);
    }

    public NormalizedEvent withIntegrationId(String integrationId) {
        return new NormalizedEvent(projectId, nodeType, source, occurredAt, actor, properties, refs, integrationId);
    }
}
