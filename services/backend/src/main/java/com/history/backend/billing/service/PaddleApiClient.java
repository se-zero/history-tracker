package com.history.backend.billing.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.common.error.BadGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// 서버가 Paddle Billing API를 부르는 클라이언트. 결제 거래의 custom_data.user_id는 여기서만 넣는다 —
// 프론트가 넣으면 다른 사용자 id로 바꿀 수 있다. API 키와 응답 원문은 로그에 남기지 않는다.
// 응답 본문에는 고객 이메일이 들어 있을 수 있어, 실패 예외의 메시지에도 본문을 싣지 않는다.
@Slf4j
@Component
public class PaddleApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PaddleProperties properties;
    private final RestClient restClient;
    private final PaddleSubscriptionParser subscriptionParser;

    public PaddleApiClient(
            PaddleProperties properties,
            @Qualifier("paddleRestClient") RestClient restClient,
            PaddleSubscriptionParser subscriptionParser
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.subscriptionParser = subscriptionParser;
    }

    // 체크아웃에 넘길 거래(txn_) 생성. 키 custom_data.user_id는 웹훅 매칭이 그대로 읽으므로 이름을 바꾸면 안 된다.
    public String createCheckoutTransaction(String priceId, UUID userId) {
        JsonNode response = post("/transactions", Map.of(
                "items", List.of(Map.of("price_id", priceId, "quantity", 1)),
                "custom_data", Map.of("user_id", userId.toString())
        ), "Paddle checkout transaction request failed.");
        String transactionId = textOrNull(response.path("data"), "id");
        if (transactionId == null) {
            throw new BadGatewayException("Paddle checkout transaction response is missing id.");
        }
        return transactionId;
    }

    // 안내 메일의 수신 주소. GitHub 로그인 이메일은 noreply일 수 있어 결제창에 적은 주소를 쓴다.
    // 이메일은 저장하지 않는다.
    public String getCustomerEmail(String customerId) {
        JsonNode data = get("/customers/" + customerId, "Paddle customer request failed.").path("data");
        return textOrNull(data, "email");
    }

    public PaddleSubscriptionSnapshot getSubscription(String subscriptionId) {
        JsonNode data = get("/subscriptions/" + subscriptionId, "Paddle subscription request failed.").path("data");
        JsonNode scheduledChange = data.path("scheduled_change");
        return new PaddleSubscriptionSnapshot(
                textOrNull(data, "status"),
                scheduledChange.isMissingNode() || scheduledChange.isNull()
                        ? null
                        : textOrNull(scheduledChange, "action"),
                parseInstant(textOrNull(data.path("current_billing_period"), "ends_at"))
        );
    }

    // 구독 엔티티 전체. 웹훅 data와 같은 모양이라 PaddleSubscriptionApplier에 그대로 넘길 수 있다.
    public PaddleSubscriptionState fetchSubscription(String subscriptionId) {
        requireApiKey();
        return subscriptionCall(() -> restClient.get()
                .uri("/subscriptions/" + subscriptionId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(String.class), "Paddle subscription request failed.");
    }

    // 해지 예약 삭제. 예약이 걸린 구독은 바로 즉시 해지되지 않아(400 subscription_locked_pending_changes)
    // 먼저 예약을 지워야 한다. 본문은 값이 null인 키여야 해서 Map.of(null 불가) 대신 singletonMap을 쓴다.
    public PaddleSubscriptionState removeScheduledChange(String subscriptionId) {
        requireApiKey();
        return subscriptionCall(() -> restClient.patch()
                .uri("/subscriptions/" + subscriptionId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Collections.singletonMap("scheduled_change", null))
                .retrieve()
                .body(String.class), "Paddle subscription update request failed.");
    }

    public PaddleSubscriptionState cancelSubscription(String subscriptionId, EffectiveFrom effectiveFrom) {
        requireApiKey();
        return subscriptionCall(() -> restClient.post()
                .uri("/subscriptions/" + subscriptionId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("effective_from", effectiveFrom.apiValue()))
                .retrieve()
                .body(String.class), "Paddle subscription cancel request failed.");
    }

    // 포털 링크는 1회용이라 호출할 때마다 새로 만든다. customer id는 캐시에서 넘긴다.
    public PortalUrls createPortalSession(String customerId, String subscriptionId) {
        JsonNode urls = post(
                "/customers/" + customerId + "/portal-sessions",
                Map.of("subscription_ids", List.of(subscriptionId)),
                "Paddle portal session request failed."
        ).path("data").path("urls");
        String overview = textOrNull(urls.path("general"), "overview");
        String cancel = null;
        for (JsonNode subscription : urls.path("subscriptions")) {
            if (subscriptionId.equals(textOrNull(subscription, "id"))) {
                cancel = textOrNull(subscription, "cancel_subscription");
                break;
            }
        }
        if (overview == null || cancel == null) {
            throw new BadGatewayException("Paddle portal session response is missing urls.");
        }
        return new PortalUrls(overview, cancel);
    }

    private JsonNode post(String path, Object body, String failureMessage) {
        requireApiKey();
        try {
            JsonNode response = restClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new BadGatewayException(failureMessage);
            }
            return response;
        } catch (BadGatewayException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("{}. status={}", failureMessage, exception.getStatusCode().value());
            throw new BadGatewayException(failureMessage);
        } catch (RestClientException exception) {
            log.warn("{}. errorType={}", failureMessage, exception.getClass().getSimpleName());
            throw new BadGatewayException(failureMessage);
        }
    }

    private JsonNode get(String path, String failureMessage) {
        requireApiKey();
        try {
            JsonNode response = restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new BadGatewayException(failureMessage);
            }
            return response;
        } catch (BadGatewayException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("{}. status={}", failureMessage, exception.getStatusCode().value());
            throw new BadGatewayException(failureMessage);
        } catch (RestClientException exception) {
            log.warn("{}. errorType={}", failureMessage, exception.getClass().getSimpleName());
            throw new BadGatewayException(failureMessage);
        }
    }

    // 구독 엔티티를 돌려주는 호출의 공통 처리. 응답은 문자열로 받아 data만 꺼내 파서에 넘긴다.
    // 오류 로그에는 상태 코드와 Paddle error.code만 남긴다 — 본문과 error.detail에는 이메일이 들어갈 수 있다.
    private PaddleSubscriptionState subscriptionCall(Supplier<String> call, String failureMessage) {
        try {
            String response = call.get();
            if (response == null) {
                throw new BadGatewayException(failureMessage);
            }
            JsonNode data = JSON.readTree(response).path("data");
            if (!data.isObject()) {
                throw new BadGatewayException(failureMessage);
            }
            return subscriptionParser.parse(JSON.writeValueAsString(data));
        } catch (BadGatewayException exception) {
            throw exception;
        } catch (PaddlePayloadException | JacksonException exception) {
            // Jackson 예외 메시지에는 본문 조각이 들어가므로 클래스 이름만 남긴다
            log.warn("{}. errorType={}", failureMessage, exception.getClass().getSimpleName());
            throw new BadGatewayException(failureMessage);
        } catch (RestClientResponseException exception) {
            String errorCode = errorCode(exception);
            if (errorCode == null) {
                log.warn("{}. status={}", failureMessage, exception.getStatusCode().value());
            } else {
                log.warn("{}. status={}, paddleErrorCode={}",
                        failureMessage, exception.getStatusCode().value(), errorCode);
            }
            throw new BadGatewayException(failureMessage);
        } catch (RestClientException exception) {
            log.warn("{}. errorType={}", failureMessage, exception.getClass().getSimpleName());
            throw new BadGatewayException(failureMessage);
        }
    }

    // 오류 본문이 JSON이 아니거나(HTML 등) error.code가 문자열이 아니면 null
    private static String errorCode(RestClientResponseException exception) {
        try {
            JsonNode code = JSON.readTree(exception.getResponseBodyAsString()).path("error").path("code");
            return code.isString() ? code.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // 키가 비었는데 호출하면 401이 나고, 그 응답을 성공처럼 해석할 위험이 있다. 호출 전에 끊는다.
    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new BadGatewayException("Paddle API key is not configured.");
        }
    }

    private String bearer() {
        return "Bearer " + properties.apiKey();
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new BadGatewayException("Paddle subscription response has an invalid period end.");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    public enum EffectiveFrom {
        NEXT_BILLING_PERIOD("next_billing_period"),
        IMMEDIATELY("immediately");

        private final String apiValue;

        EffectiveFrom(String apiValue) {
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }
    }

    public record PaddleSubscriptionSnapshot(
            String status,
            String scheduledChangeAction,
            Instant currentPeriodEndsAt
    ) {
    }

    public record PortalUrls(String overview, String cancel) {
    }
}
