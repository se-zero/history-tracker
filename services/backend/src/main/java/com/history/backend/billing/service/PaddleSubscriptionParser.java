package com.history.backend.billing.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

// Paddle 구독 엔티티(JSON) → PaddleSubscriptionState. 웹훅 data와 API 응답 data는 같은 모양이라 한 곳에서 해석한다.
@Component
public class PaddleSubscriptionParser {

    // 이 프로젝트는 ObjectMapper를 Spring 빈으로 등록하지 않아 직접 생성한다
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 웹훅 봉투에서 꺼낸 data 노드. data가 없어도(MissingNode) 예외 없이 전부 null인 상태를 돌려준다
    // — 호출부(applier)가 id 없음으로 걸러낸다.
    public PaddleSubscriptionState parse(JsonNode data) {
        JsonNode scheduledChange = data.path("scheduled_change");
        JsonNode customData = data.path("custom_data");
        JsonNode period = data.path("current_billing_period");

        return new PaddleSubscriptionState(
                textOrNull(data, "id"),
                textOrNull(data, "status"),
                textOrNull(data, "customer_id"),
                priceIds(data.path("items")),
                customData.isObject() ? textOrNull(customData, "user_id") : null,
                scheduledChange.isObject() ? textOrNull(scheduledChange, "action") : null,
                scheduledChange.isObject() ? instantOrNull(scheduledChange, "effective_at") : null,
                instantOrNull(data, "canceled_at"),
                period.isObject() ? instantOrNull(period, "ends_at") : null,
                instantOrNull(data, "next_billed_at"),
                instantOrNull(data, "updated_at")
        );
    }

    // Paddle API 응답에서 꺼낸 data의 JSON 문자열
    public PaddleSubscriptionState parse(String dataJson) {
        JsonNode data;
        try {
            data = objectMapper.readTree(dataJson);
        } catch (JsonProcessingException e) {
            throw new PaddlePayloadException("Malformed Paddle subscription payload.");
        }
        return parse(data);
    }

    // items가 없거나 배열이 아니면 null, 빈 배열은 빈 리스트다 — 둘을 같게 볼지는 호출부가 정한다.
    // 항목에 price.id가 없으면 그 자리는 null로 둔다.
    private List<String> priceIds(JsonNode items) {
        if (!items.isArray()) {
            return null;
        }
        return StreamSupport.stream(items.spliterator(), false)
                .map(item -> textOrNull(item.path("price"), "id"))
                .toList();
    }

    // 필드가 없거나(MissingNode) JSON null이면 null을 돌려준다 — Jackson의 NullNode.asText(default)는
    // default를 무시하고 문자열 "null"을 돌려주므로 isNull()을 먼저 확인해야 한다.
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Instant instantOrNull(JsonNode node, String field) {
        String raw = textOrNull(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new PaddlePayloadException("Invalid Paddle timestamp in field: " + field);
        }
    }
}
