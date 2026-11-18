package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PaddleSubscriptionParser: 웹훅 data와 Paddle API 응답 data(구독 엔티티)를 같은 PaddleSubscriptionState로 만든다.
 * 협력자가 없는 순수 파서라 실제 객체를 그대로 쓴다.
 */
@DisplayName("PaddleSubscriptionParser: 웹훅 data·API 응답 data → PaddleSubscriptionState")
class PaddleSubscriptionParserTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUBSCRIPTION_ID = "sub_01m38gzr9f7v4h6k2n8p1q3s5t";
    private static final String CUSTOMER_ID = "ctm_01m38h0a3b5c7d9e1f2g4h6j8k";
    private static final String PRICE_ID = "pri_01m38fshra9skx2brd7azsb221";
    private static final String ADDON_PRICE_ID = "pri_01addon0000000000000000000";
    private static final Instant PERIOD_ENDS_AT = Instant.parse("2026-10-24T00:00:00Z");
    private static final Instant NEXT_BILLED_AT = Instant.parse("2026-10-25T00:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-10-26T00:00:00Z");
    private static final Instant CANCELED_AT = Instant.parse("2026-10-27T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-24T02:59:30Z");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaddleSubscriptionParser parser = new PaddleSubscriptionParser();

    @Test
    @DisplayName("웹훅 data의 모든 필드를 상태 레코드로 옮긴다 (items는 price.id를 순서대로, 모르는 필드는 무시)")
    void parseReadsEveryFieldFromWebhookData() throws Exception {
        PaddleSubscriptionState state = parser.parse(webhookData(fullSubscriptionData()));

        assertThat(state).isEqualTo(fullState());
    }

    @Test
    @DisplayName("같은 구독을 웹훅 JsonNode로 파싱하든 API 응답 JSON 문자열로 파싱하든 같은 상태가 나온다")
    void parseGivesSameStateForWebhookNodeAndApiJsonString() throws Exception {
        Map<String, Object> data = fullSubscriptionData();

        PaddleSubscriptionState fromWebhook = parser.parse(webhookData(data));
        PaddleSubscriptionState fromApi = parser.parse(JSON.writeValueAsString(data));

        assertThat(fromApi).isEqualTo(fromWebhook);
        assertThat(fromApi).isEqualTo(fullState());
    }

    @Test
    @DisplayName("updated_at을 읽는다 — 순서 판정의 기준 시각이다")
    void parseReadsUpdatedAt() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", SUBSCRIPTION_ID);
        data.put("updated_at", UPDATED_AT.toString());

        assertThat(parser.parse(webhookData(data)).updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(parser.parse(JSON.writeValueAsString(data)).updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    @DisplayName("필드가 없으면 null이고, items가 없으면 priceIds도 null이다 (빈 리스트가 아니다)")
    void parseMapsMissingFieldsToNullAndMissingItemsToNullPriceIds() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", SUBSCRIPTION_ID);

        PaddleSubscriptionState fromWebhook = parser.parse(webhookData(data));
        PaddleSubscriptionState fromApi = parser.parse(JSON.writeValueAsString(data));

        assertThat(fromWebhook).isEqualTo(idOnlyState());
        assertThat(fromApi).isEqualTo(idOnlyState());
        assertThat(fromWebhook.priceIds()).isNull();
    }

    @Test
    @DisplayName("JSON null은 누락과 똑같이 null이다 — 문자열 \"null\"로 새지 않는다")
    void parseTreatsJsonNullLikeMissingField() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", SUBSCRIPTION_ID);
        data.put("status", null);
        data.put("customer_id", null);
        data.put("custom_data", null);
        data.put("items", null);
        data.put("scheduled_change", null);
        data.put("canceled_at", null);
        data.put("current_billing_period", null);
        data.put("next_billed_at", null);
        data.put("updated_at", null);

        assertThat(parser.parse(webhookData(data))).isEqualTo(idOnlyState());
        assertThat(parser.parse(JSON.writeValueAsString(data))).isEqualTo(idOnlyState());
    }

    @Test
    @DisplayName("data 노드 자체가 없어도(MissingNode) 예외 없이 전부 null인 상태가 나온다 — 호출부가 IGNORED로 처리한다")
    void parseReturnsAllNullStateForMissingDataNode() throws Exception {
        JsonNode missing = JSON.readTree("{}").path("data");

        PaddleSubscriptionState state = parser.parse(missing);

        assertThat(state).isEqualTo(new PaddleSubscriptionState(
                null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("items가 배열이 아니면 priceIds는 null이다")
    void parseTreatsNonArrayItemsAsNullPriceIds() throws Exception {
        Map<String, Object> objectItems = new LinkedHashMap<>();
        objectItems.put("id", SUBSCRIPTION_ID);
        objectItems.put("items", Map.of("price", Map.of("id", PRICE_ID)));
        Map<String, Object> textItems = new LinkedHashMap<>();
        textItems.put("id", SUBSCRIPTION_ID);
        textItems.put("items", PRICE_ID);

        assertThat(parser.parse(webhookData(objectItems)).priceIds()).isNull();
        assertThat(parser.parse(webhookData(textItems)).priceIds()).isNull();
        assertThat(parser.parse(JSON.writeValueAsString(objectItems)).priceIds()).isNull();
    }

    @Test
    @DisplayName("custom_data.user_id는 UUID 검증 없이 원문 그대로 담고, custom_data가 객체가 아니면 null이다")
    void parseKeepsCustomDataUserIdVerbatim() throws Exception {
        Map<String, Object> notUuid = new LinkedHashMap<>();
        notUuid.put("id", SUBSCRIPTION_ID);
        notUuid.put("custom_data", Map.of("user_id", "not-a-uuid"));
        Map<String, Object> notObject = new LinkedHashMap<>();
        notObject.put("id", SUBSCRIPTION_ID);
        notObject.put("custom_data", "user_id");

        assertThat(parser.parse(webhookData(notUuid)).customDataUserId()).isEqualTo("not-a-uuid");
        assertThat(parser.parse(webhookData(notObject)).customDataUserId()).isNull();
    }

    @Test
    @DisplayName("scheduled_change에 action만 있으면 effective_at은 null이다")
    void parseReadsScheduledChangeWithoutEffectiveAt() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", SUBSCRIPTION_ID);
        data.put("scheduled_change", Map.of("action", "pause"));

        PaddleSubscriptionState state = parser.parse(webhookData(data));

        assertThat(state.scheduledChangeAction()).isEqualTo("pause");
        assertThat(state.scheduledChangeEffectiveAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"sub_1\",\"updated_at\":\"yesterday\"}",
            "{\"id\":\"sub_1\",\"canceled_at\":\"yesterday\"}",
            "{\"id\":\"sub_1\",\"next_billed_at\":\"yesterday\"}",
            "{\"id\":\"sub_1\",\"current_billing_period\":{\"ends_at\":\"yesterday\"}}",
            "{\"id\":\"sub_1\",\"scheduled_change\":{\"action\":\"cancel\",\"effective_at\":\"yesterday\"}}"
    })
    @DisplayName("시각 필드 형식이 틀리면 PaddlePayloadException — 웹훅 노드든 API 문자열이든 같다")
    void parseThrowsPayloadExceptionWhenAnyTimestampIsMalformed(String dataJson) throws Exception {
        JsonNode node = JSON.readTree(dataJson);

        assertThatThrownBy(() -> parser.parse(node)).isInstanceOf(PaddlePayloadException.class);
        assertThatThrownBy(() -> parser.parse(dataJson)).isInstanceOf(PaddlePayloadException.class);
    }

    @Test
    @DisplayName("API 응답 문자열이 JSON이 아니면 PaddlePayloadException")
    void parseThrowsPayloadExceptionWhenJsonStringIsBroken() {
        assertThatThrownBy(() -> parser.parse("{not-json"))
                .isInstanceOf(PaddlePayloadException.class);
    }

    @Test
    @DisplayName("PaddlePayloadException은 RuntimeException을 직접 상속한다 (호출부가 400/502로 바꿔 던진다)")
    void payloadExceptionIsRuntimeException() {
        assertThat(PaddlePayloadException.class).hasSuperclass(RuntimeException.class);
    }

    private PaddleSubscriptionState fullState() {
        return new PaddleSubscriptionState(
                SUBSCRIPTION_ID,
                "active",
                CUSTOMER_ID,
                List.of(PRICE_ID, ADDON_PRICE_ID),
                USER_ID.toString(),
                "cancel",
                EFFECTIVE_AT,
                CANCELED_AT,
                PERIOD_ENDS_AT,
                NEXT_BILLED_AT,
                UPDATED_AT
        );
    }

    private PaddleSubscriptionState idOnlyState() {
        return new PaddleSubscriptionState(
                SUBSCRIPTION_ID, null, null, null, null, null, null, null, null, null, null);
    }

    // Paddle 구독 엔티티 — 웹훅 data와 GET /subscriptions/{id} 응답의 data가 같은 모양이다
    private Map<String, Object> fullSubscriptionData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", SUBSCRIPTION_ID);
        data.put("status", "active");
        data.put("customer_id", CUSTOMER_ID);
        data.put("custom_data", Map.of("user_id", USER_ID.toString()));
        data.put("current_billing_period", Map.of(
                "starts_at", "2026-09-24T00:00:00Z",
                "ends_at", PERIOD_ENDS_AT.toString()));
        data.put("next_billed_at", NEXT_BILLED_AT.toString());
        data.put("scheduled_change", Map.of(
                "action", "cancel",
                "effective_at", EFFECTIVE_AT.toString()));
        data.put("canceled_at", CANCELED_AT.toString());
        data.put("updated_at", UPDATED_AT.toString());
        data.put("items", List.of(
                Map.of("quantity", 1, "price", Map.of(
                        "id", PRICE_ID, "product_id", "pro_01m38fshezrep26abtbj1h1rde",
                        "unit_price", Map.of("amount", "14900", "currency_code", "KRW"))),
                Map.of("quantity", 1, "price", Map.of("id", ADDON_PRICE_ID, "product_id", "pro_01addon"))));
        data.put("management_urls", Map.of("update_payment_method", "https://example.test/pay"));
        return data;
    }

    // 실제 웹훅 봉투를 거쳐 data 노드를 꺼낸다 — 호출부(PaddleWebhookService)가 넘기는 것과 같은 모양
    private JsonNode webhookData(Map<String, Object> data) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", "evt_01");
        envelope.put("event_type", "subscription.updated");
        envelope.put("occurred_at", "2026-09-24T03:00:00Z");
        envelope.put("data", data);
        return JSON.readTree(JSON.writeValueAsString(envelope)).path("data");
    }
}
