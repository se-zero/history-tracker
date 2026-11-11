package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("PaddleApiClient: Paddle Billing API 호출")
class PaddleApiClientTest {

    private static final String API_KEY = "pdl_sdbx_apikey_test";
    private static final UUID USER_ID = UUID.fromString("d6e4a624-daf3-4561-ac6b-1ca067dd689b");
    private static final String SANDBOX = "https://sandbox-api.paddle.com";

    @Test
    @DisplayName("거래 생성은 Bearer와 custom_data.user_id를 싣고 txn id를 반환한다")
    void createCheckoutTransactionSendsUserIdAndReturnsTransactionId() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.items[0].price_id").value("pri_pro"))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.custom_data.user_id").value(USER_ID.toString()))
                .andRespond(withSuccess("""
                        { "data": { "id": "txn_1", "status": "draft" }, "meta": {} }
                        """, MediaType.APPLICATION_JSON));

        String transactionId = fixture.client.createCheckoutTransaction("pri_pro", USER_ID);

        assertThat(transactionId).isEqualTo("txn_1");
        fixture.server.verify();
    }

    @Test
    @DisplayName("거래 응답에 id가 없으면 BadGatewayException")
    void createCheckoutTransactionRejectsMissingId() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/transactions"))
                .andRespond(withSuccess("""
                        { "data": { "status": "draft" } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.createCheckoutTransaction("pri_pro", USER_ID))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle checkout transaction response is missing id.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("구독 조회는 status·해지 예약·기간 끝을 읽는다")
    void getSubscriptionReadsStatusScheduledChangeAndPeriodEnd() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "sub_1",
                            "status": "active",
                            "customer_id": "ctm_1",
                            "scheduled_change": { "action": "cancel", "effective_at": "2026-10-01T00:00:00Z" },
                            "current_billing_period": {
                              "starts_at": "2026-09-01T00:00:00Z",
                              "ends_at": "2026-10-01T00:00:00Z"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddleApiClient.PaddleSubscriptionSnapshot snapshot = fixture.client.getSubscription("sub_1");

        assertThat(snapshot.status()).isEqualTo("active");
        assertThat(snapshot.scheduledChangeAction()).isEqualTo("cancel");
        assertThat(snapshot.currentPeriodEndsAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("scheduled_change가 null이면 해지 예약 액션도 null이다")
    void getSubscriptionTreatsNullScheduledChangeAsNoAction() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "status": "past_due",
                            "scheduled_change": null,
                            "current_billing_period": { "ends_at": "2026-10-01T00:00:00Z" }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddleApiClient.PaddleSubscriptionSnapshot snapshot = fixture.client.getSubscription("sub_1");

        assertThat(snapshot.status()).isEqualTo("past_due");
        assertThat(snapshot.scheduledChangeAction()).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("구독 해지는 effective_from을 JSON으로 보낸다")
    void cancelSubscriptionSendsEffectiveFrom() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.effective_from").value("immediately"))
                .andRespond(withSuccess("""
                        { "data": { "id": "sub_1", "status": "canceled" } }
                        """, MediaType.APPLICATION_JSON));

        fixture.client.cancelSubscription("sub_1", EffectiveFrom.IMMEDIATELY);

        fixture.server.verify();
    }

    @Test
    @DisplayName("포털 세션은 subscription_ids를 싣고 overview·cancel 링크를 고른다")
    void createPortalSessionReturnsOverviewAndCancelUrls() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/customers/ctm_1/portal-sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.subscription_ids[0]").value("sub_1"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "cpls_1",
                            "urls": {
                              "general": { "overview": "https://portal.test/overview" },
                              "subscriptions": [
                                {
                                  "id": "sub_other",
                                  "cancel_subscription": "https://portal.test/other-cancel"
                                },
                                {
                                  "id": "sub_1",
                                  "cancel_subscription": "https://portal.test/cancel"
                                }
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddleApiClient.PortalUrls urls = fixture.client.createPortalSession("ctm_1", "sub_1");

        assertThat(urls.overview()).isEqualTo("https://portal.test/overview");
        assertThat(urls.cancel()).isEqualTo("https://portal.test/cancel");
        fixture.server.verify();
    }

    @Test
    @DisplayName("포털 응답에 해당 구독의 해지 링크가 없으면 BadGatewayException")
    void createPortalSessionRejectsMissingCancelUrl() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/customers/ctm_1/portal-sessions"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "urls": {
                              "general": { "overview": "https://portal.test/overview" },
                              "subscriptions": []
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.createPortalSession("ctm_1", "sub_1"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle portal session response is missing urls.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("4xx·5xx·네트워크 오류는 BadGatewayException이고 응답 본문은 메시지에 없다")
    void requestsMapHttpAndNetworkFailuresToBadGateway() {
        Fixture badRequest = fixture(configured("sandbox"));
        badRequest.server.expect(once(), requestTo(SANDBOX + "/transactions"))
                .andRespond(withBadRequest().body("{\"error\":\"secret-body\"}"));
        assertThatThrownBy(() -> badRequest.client.createCheckoutTransaction("pri_pro", USER_ID))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle checkout transaction request failed.")
                .hasMessageNotContaining("secret-body");
        badRequest.server.verify();

        Fixture serverError = fixture(configured("sandbox"));
        serverError.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> serverError.client.getSubscription("sub_1"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle subscription request failed.");
        serverError.server.verify();

        Fixture network = fixture(configured("sandbox"));
        network.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1/cancel"))
                .andRespond(withException(new java.io.IOException("connect timed out")));
        assertThatThrownBy(() -> network.client.cancelSubscription("sub_1", EffectiveFrom.NEXT_BILLING_PERIOD))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle subscription cancel request failed.");
        network.server.verify();
    }

    @Test
    @DisplayName("고객 조회는 이메일만 읽고, 이메일이 없으면 null이다")
    void getCustomerEmailReadsEmailOnly() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/customers/ctm_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess("""
                        { "data": { "id": "ctm_1", "email": "payer@example.com", "name": "Payer" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.getCustomerEmail("ctm_1")).isEqualTo("payer@example.com");
        fixture.server.verify();

        Fixture missing = fixture(configured("sandbox"));
        missing.server.expect(once(), requestTo(SANDBOX + "/customers/ctm_2"))
                .andRespond(withSuccess("""
                        { "data": { "id": "ctm_2", "email": null } }
                        """, MediaType.APPLICATION_JSON));
        assertThat(missing.client.getCustomerEmail("ctm_2")).isNull();
        missing.server.verify();
    }

    @Test
    @DisplayName("API 키가 비면 호출하지 않고 BadGatewayException")
    void blankApiKeyFailsBeforeCallingPaddle() {
        Fixture fixture = fixture(configured("sandbox", "  "));
        assertThatThrownBy(() -> fixture.client.getSubscription("sub_1"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle API key is not configured.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("environment가 production일 때만 라이브 API, 그 외에는 샌드박스")
    void apiBaseUrlSelectsLiveOnlyForProduction() {
        assertThat(configured("sandbox").apiBaseUrl()).isEqualTo("https://sandbox-api.paddle.com");
        assertThat(configured(null).apiBaseUrl()).isEqualTo("https://sandbox-api.paddle.com");
        assertThat(configured("production").apiBaseUrl()).isEqualTo("https://api.paddle.com");
    }

    @Test
    @DisplayName("결제 가능 여부는 API 키·클라이언트 토큰·가격 ID가 모두 있을 때만 true")
    void checkoutConfiguredRequiresKeyTokenAndPrice() {
        assertThat(configured("sandbox").isCheckoutConfigured()).isTrue();
        assertThat(new PaddleProperties("whsec", Duration.ofSeconds(5), "pri_pro", "", "sandbox", "test_token")
                .isCheckoutConfigured()).isFalse();
        assertThat(new PaddleProperties("whsec", Duration.ofSeconds(5), "pri_pro", API_KEY, "sandbox", " ")
                .isCheckoutConfigured()).isFalse();
        assertThat(new PaddleProperties("whsec", Duration.ofSeconds(5), "", API_KEY, "sandbox", "test_token")
                .isCheckoutConfigured()).isFalse();
    }

    // ── 환불 즉시 해지용: 구독 전체 조회 · 예약 삭제(PATCH) · 해지 응답 해석 ──

    @Test
    @DisplayName("구독 전체 조회는 data를 PaddleSubscriptionState로 해석한다 (상태·고객·가격 전부·custom_data·예약·기간·시각)")
    void fetchSubscriptionParsesFullSubscriptionEntity() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "sub_1",
                            "status": "active",
                            "customer_id": "ctm_1",
                            "custom_data": { "user_id": "%s" },
                            "items": [
                              { "quantity": 1, "price": { "id": "pri_pro", "product_id": "pro_1" } },
                              { "quantity": 1, "price": { "id": "pri_addon", "product_id": "pro_2" } }
                            ],
                            "scheduled_change": { "action": "cancel", "effective_at": "2026-12-10T00:00:00Z" },
                            "canceled_at": null,
                            "current_billing_period": {
                              "starts_at": "2026-11-10T00:00:00Z",
                              "ends_at": "2026-12-10T00:00:00Z"
                            },
                            "next_billed_at": "2026-12-10T00:00:00Z",
                            "updated_at": "2026-11-10T03:00:00Z"
                          },
                          "meta": { "request_id": "req_1" }
                        }
                        """.formatted(USER_ID), MediaType.APPLICATION_JSON));

        PaddleSubscriptionState state = fixture.client.fetchSubscription("sub_1");

        assertThat(state).isEqualTo(new PaddleSubscriptionState(
                "sub_1", "active", "ctm_1", List.of("pri_pro", "pri_addon"), USER_ID.toString(),
                "cancel", Instant.parse("2026-12-10T00:00:00Z"), null,
                Instant.parse("2026-12-10T00:00:00Z"), Instant.parse("2026-12-10T00:00:00Z"),
                Instant.parse("2026-11-10T03:00:00Z")));
        fixture.server.verify();
    }

    @Test
    @DisplayName("예약 삭제는 PATCH /subscriptions/{id}에 본문 정확히 {\"scheduled_change\": null}을 보내고 응답을 state로 해석한다")
    void removeScheduledChangeSendsPatchWithNullScheduledChange() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 키가 빠지거나(null 직렬화 누락) 다른 필드가 끼면 STRICT 비교가 잡는다
                .andExpect(content().json("{\"scheduled_change\": null}", JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "sub_1",
                            "status": "active",
                            "customer_id": "ctm_1",
                            "scheduled_change": null,
                            "current_billing_period": { "ends_at": "2026-12-10T00:00:00Z" },
                            "updated_at": "2026-11-10T03:00:05Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddleSubscriptionState state = fixture.client.removeScheduledChange("sub_1");

        assertThat(state.subscriptionId()).isEqualTo("sub_1");
        assertThat(state.status()).isEqualTo("active");
        assertThat(state.scheduledChangeAction()).isNull();
        assertThat(state.updatedAt()).isEqualTo(Instant.parse("2026-11-10T03:00:05Z"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("즉시 해지 응답은 canceled state로 해석한다 (canceled_at·updated_at 포함, 기간·결제 예정일은 null)")
    void cancelSubscriptionImmediatelyReturnsCanceledState() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.effective_from").value("immediately"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "sub_1",
                            "status": "canceled",
                            "customer_id": "ctm_1",
                            "items": [ { "price": { "id": "pri_pro", "product_id": "pro_1" } } ],
                            "scheduled_change": null,
                            "canceled_at": "2026-11-10T03:00:10Z",
                            "current_billing_period": null,
                            "next_billed_at": null,
                            "updated_at": "2026-11-10T03:00:10Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddleSubscriptionState state = fixture.client.cancelSubscription("sub_1", EffectiveFrom.IMMEDIATELY);

        assertThat(state.subscriptionId()).isEqualTo("sub_1");
        assertThat(state.status()).isEqualTo("canceled");
        assertThat(state.customerId()).isEqualTo("ctm_1");
        assertThat(state.priceIds()).containsExactly("pri_pro");
        assertThat(state.canceledAt()).isEqualTo(Instant.parse("2026-11-10T03:00:10Z"));
        assertThat(state.updatedAt()).isEqualTo(Instant.parse("2026-11-10T03:00:10Z"));
        assertThat(state.currentPeriodEndsAt()).isNull();
        assertThat(state.nextBilledAt()).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("기간 말 해지 응답은 해지 예약(scheduled_change)이 걸린 active state로 해석한다 (예약 복구에 쓰인다)")
    void cancelSubscriptionAtPeriodEndReturnsScheduledState() {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.effective_from").value("next_billing_period"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "sub_1",
                            "status": "active",
                            "customer_id": "ctm_1",
                            "scheduled_change": { "action": "cancel", "effective_at": "2026-12-10T00:00:00Z" },
                            "current_billing_period": { "ends_at": "2026-12-10T00:00:00Z" },
                            "updated_at": "2026-11-10T03:00:20Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddleSubscriptionState state = fixture.client.cancelSubscription("sub_1", EffectiveFrom.NEXT_BILLING_PERIOD);

        assertThat(state.status()).isEqualTo("active");
        assertThat(state.scheduledChangeAction()).isEqualTo("cancel");
        assertThat(state.scheduledChangeEffectiveAt()).isEqualTo(Instant.parse("2026-12-10T00:00:00Z"));
        fixture.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionCalls")
    @DisplayName("4xx·5xx·네트워크 오류는 BadGatewayException이고 응답 본문은 메시지에 없다")
    void subscriptionCallsMapHttpAndNetworkFailuresToBadGateway(Call call) {
        Fixture badRequest = fixture(configured("sandbox"));
        badRequest.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withBadRequest().body("{\"error\":{\"code\":\"bad\",\"detail\":\"secret-body\"}}"));
        assertThatThrownBy(() -> call.invoke().apply(badRequest.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("secret-body");
        badRequest.server.verify();

        Fixture notFound = fixture(configured("sandbox"));
        notFound.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withResourceNotFound());
        assertThatThrownBy(() -> call.invoke().apply(notFound.client))
                .isInstanceOf(BadGatewayException.class);
        notFound.server.verify();

        Fixture serverError = fixture(configured("sandbox"));
        serverError.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withServerError());
        assertThatThrownBy(() -> call.invoke().apply(serverError.client))
                .isInstanceOf(BadGatewayException.class);
        serverError.server.verify();

        Fixture network = fixture(configured("sandbox"));
        network.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withException(new java.io.IOException("connect timed out")));
        assertThatThrownBy(() -> call.invoke().apply(network.client))
                .isInstanceOf(BadGatewayException.class);
        network.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionCalls")
    @DisplayName("성공 응답이어도 본문이 비었거나 깨졌거나 data의 시각을 해석할 수 없으면 BadGatewayException")
    void subscriptionCallsRejectUnreadableSuccessBody(Call call) {
        Fixture empty = fixture(configured("sandbox"));
        empty.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withSuccess());
        assertThatThrownBy(() -> call.invoke().apply(empty.client))
                .isInstanceOf(BadGatewayException.class);
        empty.server.verify();

        Fixture malformed = fixture(configured("sandbox"));
        malformed.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withSuccess("not-json-secret", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> call.invoke().apply(malformed.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("not-json-secret");
        malformed.server.verify();

        Fixture invalidTimestamp = fixture(configured("sandbox"));
        invalidTimestamp.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withSuccess("""
                        { "data": { "id": "sub_1", "status": "active", "updated_at": "yesterday" } }
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> call.invoke().apply(invalidTimestamp.client))
                .isInstanceOf(BadGatewayException.class);
        invalidTimestamp.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionCalls")
    @DisplayName("API 키가 비면 어느 호출이든 Paddle을 부르지 않고 BadGatewayException")
    void subscriptionCallsFailBeforeCallingPaddleWhenApiKeyIsBlank(Call call) {
        Fixture fixture = fixture(configured("sandbox", "  "));

        assertThatThrownBy(() -> call.invoke().apply(fixture.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle API key is not configured.");
        fixture.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionCalls")
    @DisplayName("오류 로그에는 상태 코드와 Paddle error.code만 남고 detail(이메일 포함)·본문은 남지 않는다")
    void subscriptionCallsLogStatusAndErrorCodeButNeverDetailOrBody(Call call, CapturedOutput output) {
        String body = "{\"error\":{\"type\":\"request_error\",\"code\":\"subscription_locked_pending_changes\","
                + "\"detail\":\"Subscription for payer@example.com has pending changes.\"}}";
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + call.path()))
                .andExpect(method(call.method()))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body(body));

        assertThatThrownBy(() -> call.invoke().apply(fixture.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("payer@example.com");

        assertThat(output.getAll())
                .contains("status=400")
                .contains("subscription_locked_pending_changes")
                .doesNotContain("payer@example.com")
                .doesNotContain("has pending changes")
                .doesNotContain(body);
        fixture.server.verify();
    }

    @ParameterizedTest(name = "오류 본문 = {0}")
    @ValueSource(strings = {
            "<html>payer@example.com</html>",
            "{\"message\":\"payer@example.com\"}",
            "{\"error\":\"payer@example.com\"}",
            "[\"payer@example.com\"]"
    })
    @DisplayName("오류 본문이 Paddle 오류 형식이 아니어도(HTML·error 없음·error가 문자열·배열) BadGatewayException이고 본문은 로그에 없다")
    void fetchSubscriptionLogsOnlyStatusWhenErrorBodyHasNoErrorCode(String body, CapturedOutput output) {
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), requestTo(SANDBOX + "/subscriptions/sub_1"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body(body));

        assertThatThrownBy(() -> fixture.client.fetchSubscription("sub_1"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("payer@example.com");

        assertThat(output.getAll())
                .contains("status=502")
                .doesNotContain("payer@example.com");
        fixture.server.verify();
    }

    // ── 동기화용: 살아 있는 구독 전체 조회(price_id 필터, 페이지) · ID 묶음 조회 ──

    @Test
    @DisplayName("살아 있는 구독 조회는 price_id·status 4종·per_page=200으로 GET /subscriptions를 부르고, 마지막 페이지(has_more=false)에서 멈춘다")
    void listLiveSubscriptionsSendsPriceStatusAndPageSizeQuery() {
        Fixture fixture = fixture(configured("sandbox"));
        expectLivePage(fixture.server, null, page(false, null, subscriptionJson("sub_1"), subscriptionJson("sub_2")));

        List<PaddleSubscriptionState> result = fixture.client.listLiveSubscriptions("pri_pro");

        assertThat(result).extracting(PaddleSubscriptionState::subscriptionId).containsExactly("sub_1", "sub_2");
        // 기대한 요청은 한 번뿐이다 — 한 번 더 부르면 "No further requests expected"로 실패한다
        fixture.server.verify();
    }

    @Test
    @DisplayName("각 원소는 구독 전체 조회와 같은 방식으로 PaddleSubscriptionState로 해석한다 (가격 전부·custom_data·예약·기간·시각)")
    void listLiveSubscriptionsParsesEachElementLikeFetchSubscription() {
        Fixture fixture = fixture(configured("sandbox"));
        expectLivePage(fixture.server, null, page(false, null, """
                {
                  "id": "sub_1",
                  "status": "active",
                  "customer_id": "ctm_1",
                  "custom_data": { "user_id": "%s" },
                  "items": [
                    { "quantity": 1, "price": { "id": "pri_pro", "product_id": "pro_1" } },
                    { "quantity": 1, "price": { "id": "pri_addon", "product_id": "pro_2" } }
                  ],
                  "scheduled_change": { "action": "cancel", "effective_at": "2026-12-10T00:00:00Z" },
                  "canceled_at": null,
                  "current_billing_period": {
                    "starts_at": "2026-11-10T00:00:00Z",
                    "ends_at": "2026-12-10T00:00:00Z"
                  },
                  "next_billed_at": "2026-12-10T00:00:00Z",
                  "updated_at": "2026-11-10T03:00:00Z"
                }
                """.formatted(USER_ID), """
                {
                  "id": "sub_2",
                  "status": "paused",
                  "customer_id": "ctm_2",
                  "items": [ { "price": { "id": "pri_pro" } } ],
                  "scheduled_change": null,
                  "canceled_at": null,
                  "current_billing_period": null,
                  "next_billed_at": null,
                  "updated_at": "2026-11-10T04:00:00Z"
                }
                """));

        List<PaddleSubscriptionState> result = fixture.client.listLiveSubscriptions("pri_pro");

        assertThat(result).containsExactly(
                new PaddleSubscriptionState(
                        "sub_1", "active", "ctm_1", List.of("pri_pro", "pri_addon"), USER_ID.toString(),
                        "cancel", Instant.parse("2026-12-10T00:00:00Z"), null,
                        Instant.parse("2026-12-10T00:00:00Z"), Instant.parse("2026-12-10T00:00:00Z"),
                        Instant.parse("2026-11-10T03:00:00Z")),
                new PaddleSubscriptionState(
                        "sub_2", "paused", "ctm_2", List.of("pri_pro"), null,
                        null, null, null, null, null, Instant.parse("2026-11-10T04:00:00Z")));
        fixture.server.verify();
    }

    @Test
    @DisplayName("다음 페이지는 meta.next URL에서 뽑지 않고 직전 페이지 마지막 구독의 id를 after로 넘겨 이어 받는다 (필터는 그대로 유지)")
    void listLiveSubscriptionsContinuesWithLastIdOfPreviousPageAsAfter() {
        Fixture fixture = fixture(configured("sandbox"));
        // next URL의 after가 마지막 id와 다르게 오더라도 따라가면 안 된다 — 커서는 마지막 id다
        String misleadingNext = SANDBOX + "/subscriptions?after=sub_from_next_url&per_page=200";
        expectLivePage(fixture.server, null,
                page(true, misleadingNext, subscriptionJson("sub_a"), subscriptionJson("sub_b")));
        expectLivePage(fixture.server, "sub_b",
                page(true, misleadingNext, subscriptionJson("sub_c")));
        expectLivePage(fixture.server, "sub_c",
                page(false, null, subscriptionJson("sub_d")));

        List<PaddleSubscriptionState> result = fixture.client.listLiveSubscriptions("pri_pro");

        assertThat(result).extracting(PaddleSubscriptionState::subscriptionId)
                .containsExactly("sub_a", "sub_b", "sub_c", "sub_d");
        fixture.server.verify();
    }

    @Test
    @DisplayName("멈춤 조건 1: 빈 페이지가 오면 has_more=true여도 더 부르지 않는다")
    void listLiveSubscriptionsStopsOnEmptyPage() {
        Fixture fixture = fixture(configured("sandbox"));
        expectLivePage(fixture.server, null, page(true, null, subscriptionJson("sub_a")));
        expectLivePage(fixture.server, "sub_a", page(true, null));

        List<PaddleSubscriptionState> result = fixture.client.listLiveSubscriptions("pri_pro");

        assertThat(result).extracting(PaddleSubscriptionState::subscriptionId).containsExactly("sub_a");
        fixture.server.verify();
    }

    @Test
    @DisplayName("멈춤 조건 2: 같은 커서가 반복되면(after로 받은 페이지의 마지막 id가 그 after와 같음) has_more=true여도 더 부르지 않는다")
    void listLiveSubscriptionsStopsWhenCursorRepeats() {
        Fixture fixture = fixture(configured("sandbox"));
        expectLivePage(fixture.server, null,
                page(true, null, subscriptionJson("sub_a"), subscriptionJson("sub_b")));
        // 서버가 after를 무시하고 같은 페이지를 되돌려주는 경우 — 따라가면 끝나지 않는다
        expectLivePage(fixture.server, "sub_b",
                page(true, null, subscriptionJson("sub_a"), subscriptionJson("sub_b")));

        List<PaddleSubscriptionState> result = fixture.client.listLiveSubscriptions("pri_pro");

        assertThat(result).extracting(PaddleSubscriptionState::subscriptionId).contains("sub_a", "sub_b");
        fixture.server.verify();
    }

    @Test
    @DisplayName("멈춤 조건 3: 페이지 상한(50)에 닿으면 has_more=true여도 51번째 요청은 하지 않는다")
    void listLiveSubscriptionsStopsAtPageLimit() {
        Fixture fixture = fixture(configured("sandbox"));
        for (int page = 1; page <= 50; page++) {
            String after = page == 1 ? null : String.format("sub_%02d", page - 1);
            expectLivePage(fixture.server, after,
                    page(true, null, subscriptionJson(String.format("sub_%02d", page))));
        }

        List<PaddleSubscriptionState> result = fixture.client.listLiveSubscriptions("pri_pro");

        assertThat(result).hasSize(50);
        assertThat(result.get(49).subscriptionId()).isEqualTo("sub_50");
        fixture.server.verify();
    }

    @Test
    @DisplayName("ID 묶음 조회는 id(쉼표 목록)와 per_page=200만 싣는다 — status·price_id 필터가 있으면 해지된 구독이 응답에서 빠진다")
    void listSubscriptionsByIdsSendsOnlyIdsAndPageSize() {
        Fixture fixture = fixture(configured("sandbox"));
        expectIdsPage(fixture.server, List.of("sub_1", "sub_2", "sub_3"), page(false, null,
                subscriptionJson("sub_1", "active"),
                subscriptionJson("sub_2", "canceled"),
                subscriptionJson("sub_3", "paused")));

        List<PaddleSubscriptionState> result = fixture.client.listSubscriptionsByIds(List.of("sub_1", "sub_2", "sub_3"));

        assertThat(result).extracting(PaddleSubscriptionState::subscriptionId, PaddleSubscriptionState::status)
                .containsExactly(
                        tuple("sub_1", "active"),
                        tuple("sub_2", "canceled"),
                        tuple("sub_3", "paused"));
        fixture.server.verify();
    }

    @ParameterizedTest(name = "ID {0}개 → 요청 {1}")
    @MethodSource("idChunkCases")
    @DisplayName("ID는 100개씩 나눠 조회하고 결과를 이어 붙인다")
    void listSubscriptionsByIdsSplitsIdsIntoChunksOfOneHundred(int total, List<Integer> chunkSizes) {
        Fixture fixture = fixture(configured("sandbox"));
        List<String> ids = IntStream.range(0, total).mapToObj(i -> String.format("sub_%03d", i)).toList();
        int from = 0;
        for (int chunkSize : chunkSizes) {
            List<String> chunk = ids.subList(from, from + chunkSize);
            expectIdsPage(fixture.server, chunk,
                    page(false, null, chunk.stream().map(this::subscriptionJson).toArray(String[]::new)));
            from += chunkSize;
        }

        List<PaddleSubscriptionState> result = fixture.client.listSubscriptionsByIds(ids);

        assertThat(result).extracting(PaddleSubscriptionState::subscriptionId).containsExactlyElementsOf(ids);
        fixture.server.verify();
    }

    static Stream<Arguments> idChunkCases() {
        return Stream.of(
                Arguments.of(1, List.of(1)),
                Arguments.of(100, List.of(100)),
                Arguments.of(101, List.of(100, 1)),
                Arguments.of(250, List.of(100, 100, 50))
        );
    }

    @Test
    @DisplayName("ID 목록이 비어 있으면 Paddle을 부르지 않고 빈 리스트를 돌려준다")
    void listSubscriptionsByIdsReturnsEmptyWithoutCallingPaddleWhenIdsAreEmpty() {
        Fixture fixture = fixture(configured("sandbox"));

        List<PaddleSubscriptionState> result = fixture.client.listSubscriptionsByIds(List.of());

        assertThat(result).isEmpty();
        // 기대 요청을 하나도 등록하지 않았으므로 어떤 호출이든 실패한다
        fixture.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionListCalls")
    @DisplayName("목록 조회의 4xx·5xx·네트워크 오류는 BadGatewayException이고 응답 본문은 메시지에 없다")
    void subscriptionListCallsMapHttpAndNetworkFailuresToBadGateway(ListCall call) {
        Fixture badRequest = fixture(configured("sandbox"));
        badRequest.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withBadRequest().body("{\"error\":{\"code\":\"bad\",\"detail\":\"secret-body\"}}"));
        assertThatThrownBy(() -> call.invoke().apply(badRequest.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("secret-body");
        badRequest.server.verify();

        Fixture notFound = fixture(configured("sandbox"));
        notFound.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());
        assertThatThrownBy(() -> call.invoke().apply(notFound.client))
                .isInstanceOf(BadGatewayException.class);
        notFound.server.verify();

        Fixture serverError = fixture(configured("sandbox"));
        serverError.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        assertThatThrownBy(() -> call.invoke().apply(serverError.client))
                .isInstanceOf(BadGatewayException.class);
        serverError.server.verify();

        Fixture network = fixture(configured("sandbox"));
        network.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withException(new java.io.IOException("connect timed out")));
        assertThatThrownBy(() -> call.invoke().apply(network.client))
                .isInstanceOf(BadGatewayException.class);
        network.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionListCalls")
    @DisplayName("목록 조회가 성공 응답이어도 본문이 비었거나 깨졌거나 원소의 시각을 해석할 수 없으면 BadGatewayException")
    void subscriptionListCallsRejectUnreadableSuccessBody(ListCall call) {
        Fixture empty = fixture(configured("sandbox"));
        empty.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());
        assertThatThrownBy(() -> call.invoke().apply(empty.client))
                .isInstanceOf(BadGatewayException.class);
        empty.server.verify();

        Fixture malformed = fixture(configured("sandbox"));
        malformed.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("not-json-secret", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> call.invoke().apply(malformed.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("not-json-secret");
        malformed.server.verify();

        Fixture invalidTimestamp = fixture(configured("sandbox"));
        invalidTimestamp.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "data": [ { "id": "sub_1", "status": "active", "updated_at": "yesterday" } ] }
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> call.invoke().apply(invalidTimestamp.client))
                .isInstanceOf(BadGatewayException.class);
        invalidTimestamp.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionListCalls")
    @DisplayName("API 키가 비면 목록 조회도 Paddle을 부르지 않고 BadGatewayException")
    void subscriptionListCallsFailBeforeCallingPaddleWhenApiKeyIsBlank(ListCall call) {
        Fixture fixture = fixture(configured("sandbox", "  "));

        assertThatThrownBy(() -> call.invoke().apply(fixture.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Paddle API key is not configured.");
        fixture.server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionListCalls")
    @DisplayName("목록 조회의 오류 로그에는 상태 코드와 Paddle error.code만 남고 detail(이메일 포함)·본문은 남지 않는다")
    void subscriptionListCallsLogStatusAndErrorCodeButNeverDetailOrBody(ListCall call, CapturedOutput output) {
        String body = "{\"error\":{\"type\":\"request_error\",\"code\":\"too_many_requests\","
                + "\"detail\":\"Requests from payer@example.com were rate limited.\"}}";
        Fixture fixture = fixture(configured("sandbox"));
        fixture.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON).body(body));

        assertThatThrownBy(() -> call.invoke().apply(fixture.client))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("payer@example.com");

        assertThat(output.getAll())
                .contains("status=429")
                .contains("too_many_requests")
                .doesNotContain("payer@example.com")
                .doesNotContain("were rate limited")
                .doesNotContain(body);
        fixture.server.verify();
    }

    @Test
    @DisplayName("둘째 페이지 이후가 실패하면 앞 페이지 결과만 돌려주지 않고 BadGatewayException을 던진다")
    void listLiveSubscriptionsFailsWhenALaterPageFails() {
        Fixture fixture = fixture(configured("sandbox"));
        expectLivePage(fixture.server, null, page(true, null, subscriptionJson("sub_a")));
        fixture.server.expect(once(), subscriptionsEndpoint())
                .andExpect(queryParam("after", "sub_a"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.listLiveSubscriptions("pri_pro"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("ID 묶음 조회에서 뒤쪽 묶음이 실패하면 앞 묶음 결과만 돌려주지 않고 BadGatewayException을 던진다")
    void listSubscriptionsByIdsFailsWhenALaterChunkFails() {
        Fixture fixture = fixture(configured("sandbox"));
        List<String> ids = IntStream.range(0, 150).mapToObj(i -> String.format("sub_%03d", i)).toList();
        expectIdsPage(fixture.server, ids.subList(0, 100),
                page(false, null, ids.subList(0, 100).stream().map(this::subscriptionJson).toArray(String[]::new)));
        fixture.server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.listSubscriptionsByIds(ids))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    static Stream<Call> subscriptionCalls() {
        return Stream.of(
                new Call("fetchSubscription", HttpMethod.GET, "/subscriptions/sub_1",
                        client -> client.fetchSubscription("sub_1")),
                new Call("removeScheduledChange", HttpMethod.PATCH, "/subscriptions/sub_1",
                        client -> client.removeScheduledChange("sub_1")),
                new Call("cancelSubscription(IMMEDIATELY)", HttpMethod.POST, "/subscriptions/sub_1/cancel",
                        client -> client.cancelSubscription("sub_1", EffectiveFrom.IMMEDIATELY)),
                new Call("cancelSubscription(NEXT_BILLING_PERIOD)", HttpMethod.POST, "/subscriptions/sub_1/cancel",
                        client -> client.cancelSubscription("sub_1", EffectiveFrom.NEXT_BILLING_PERIOD))
        );
    }

    // 파라미터화 테스트가 호출 하나(메서드·경로·실행)를 값으로 넘기기 위한 묶음 — 이름은 테스트 표시용
    private record Call(
            String name,
            HttpMethod method,
            String path,
            Function<PaddleApiClient, PaddleSubscriptionState> invoke
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ListCall> subscriptionListCalls() {
        return Stream.of(
                new ListCall("listLiveSubscriptions", client -> client.listLiveSubscriptions("pri_pro")),
                new ListCall("listSubscriptionsByIds", client -> client.listSubscriptionsByIds(List.of("sub_1")))
        );
    }

    // 파라미터화 테스트가 목록 조회 호출 하나를 값으로 넘기기 위한 묶음 — 이름은 테스트 표시용
    private record ListCall(String name, Function<PaddleApiClient, List<PaddleSubscriptionState>> invoke) {
        @Override
        public String toString() {
            return name;
        }
    }

    // 살아 있는 구독 목록의 한 페이지 요청 기대 — after가 null이면 첫 페이지(커서 없음)
    private void expectLivePage(MockRestServiceServer server, String after, String responseBody) {
        ResponseActions expectation = server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(queryParam("price_id", "pri_pro"))
                .andExpect(queryParamCsv("status", List.of("active", "trialing", "past_due", "paused")))
                .andExpect(queryParam("per_page", "200"))
                .andExpect(queryParamAbsent("id"));
        expectation.andExpect(after == null ? queryParamAbsent("after") : queryParam("after", after));
        expectation.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    // ID 묶음 한 번의 요청 기대 — id와 per_page 외에는 아무 필터도 없어야 한다
    private void expectIdsPage(MockRestServiceServer server, List<String> ids, String responseBody) {
        server.expect(once(), subscriptionsEndpoint())
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(queryParamCsv("id", ids))
                .andExpect(queryParam("per_page", "200"))
                .andExpect(queryParamAbsent("status", "price_id", "after"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    // 쿼리 문자열은 원문이 아니라 디코딩한 값으로 비교한다 — 쉼표가 %2C로 인코딩돼 나가도 같은 요청이다
    private static RequestMatcher subscriptionsEndpoint() {
        return request -> {
            URI uri = request.getURI();
            assertThat(uri.getScheme() + "://" + uri.getHost() + uri.getPath()).isEqualTo(SANDBOX + "/subscriptions");
        };
    }

    // 쉼표로 이은 값의 구성 원소를 순서 없이 비교한다 (status는 순서가 의미 없고, id는 묶음에 든 원소가 중요하다)
    private static RequestMatcher queryParamCsv(String name, List<String> expected) {
        return request -> {
            String raw = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(name);
            assertThat(raw).as("query param [%s]", name).isNotNull();
            assertThat(List.of(UriUtils.decode(raw, StandardCharsets.UTF_8).split(",")))
                    .as("query param [%s]", name)
                    .containsExactlyInAnyOrderElementsOf(expected);
        };
    }

    private static RequestMatcher queryParamAbsent(String... names) {
        return request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams())
                .doesNotContainKeys(names);
    }

    // Paddle 목록 응답: data 배열과 meta.pagination. next는 null이면 필드를 비운다.
    private static String page(boolean hasMore, String next, String... subscriptions) {
        return """
                {
                  "data": [%s],
                  "meta": {
                    "request_id": "req_1",
                    "pagination": { "per_page": 200, "next": %s, "has_more": %s, "estimated_total": 3 }
                  }
                }
                """.formatted(
                String.join(",", subscriptions),
                next == null ? "null" : "\"" + next + "\"",
                hasMore);
    }

    private String subscriptionJson(String id) {
        return subscriptionJson(id, "active");
    }

    private String subscriptionJson(String id, String status) {
        return """
                {
                  "id": "%s",
                  "status": "%s",
                  "customer_id": "ctm_1",
                  "items": [ { "price": { "id": "pri_pro" } } ],
                  "current_billing_period": { "ends_at": "2026-12-10T00:00:00Z" },
                  "updated_at": "2026-11-10T03:00:00Z"
                }
                """.formatted(id, status);
    }

    private Fixture fixture(PaddleProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new PaddleApiClient(properties, builder.build(), new PaddleSubscriptionParser()), server);
    }

    private PaddleProperties configured(String environment) {
        return configured(environment, API_KEY);
    }

    private PaddleProperties configured(String environment, String apiKey) {
        return new PaddleProperties(
                "whsec", Duration.ofSeconds(5), "pri_pro", apiKey, environment, "test_token");
    }

    private record Fixture(PaddleApiClient client, MockRestServiceServer server) {
    }
}
