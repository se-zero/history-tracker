package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.service.PaddleApiClient.EffectiveFrom;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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

    private Fixture fixture(PaddleProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new PaddleApiClient(properties, builder.build()), server);
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
