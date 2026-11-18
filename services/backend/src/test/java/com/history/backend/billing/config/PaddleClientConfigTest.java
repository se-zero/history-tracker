package com.history.backend.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.service.PaddleApiClient;
import com.history.backend.billing.service.PaddleSubscriptionParser;
import com.history.backend.billing.service.PaddleSubscriptionState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * PaddleClientConfig: paddleRestClient가 쓰는 요청 팩토리가 실제 HTTP 연결로 PATCH를 보낼 수 있는지 확인한다.
 * PaddleApiClientTest의 MockRestServiceServer는 요청 팩토리를 바꿔 끼우므로 이 결함을 못 잡는다 —
 * HttpURLConnection 기반 팩토리(SimpleClientHttpRequestFactory)는 setRequestMethod("PATCH")에서
 * ProtocolException을 던진다. 그래서 JDK 내장 HttpServer를 로컬에 띄워 실제 소켓으로 보낸다.
 */
@DisplayName("PaddleClientConfig: 요청 팩토리는 실제 연결로 PATCH를 보낼 수 있다")
class PaddleClientConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<RecordedRequest> received = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.set(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(body, StandardCharsets.UTF_8)));
            byte[] response = """
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
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("removeScheduledChange는 실제 연결로 PATCH /subscriptions/sub_1에 {\"scheduled_change\": null}을 보내고 응답을 해석한다")
    void removeScheduledChangeSendsRealPatchRequest() throws Exception {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(PaddleClientConfig.requestFactory())
                .build();
        PaddleApiClient client = new PaddleApiClient(properties(), restClient, new PaddleSubscriptionParser());

        PaddleSubscriptionState state = client.removeScheduledChange("sub_1");

        RecordedRequest request = received.get();
        assertThat(request).as("서버가 요청을 받아야 한다").isNotNull();
        assertThat(request.method()).isEqualTo("PATCH");
        assertThat(request.path()).isEqualTo("/subscriptions/sub_1");
        assertThat(request.authorization()).isEqualTo("Bearer pdl_test_apikey");
        assertThat(JSON.readTree(request.body())).isEqualTo(JSON.readTree("{\"scheduled_change\":null}"));
        assertThat(state.subscriptionId()).isEqualTo("sub_1");
        assertThat(state.status()).isEqualTo("active");
        assertThat(state.scheduledChangeAction()).isNull();
        assertThat(state.updatedAt()).isEqualTo(Instant.parse("2026-11-10T03:00:05Z"));
    }

    private PaddleProperties properties() {
        return new PaddleProperties(
                "whsec", Duration.ofSeconds(5), "pri_pro", "pdl_test_apikey", "sandbox", "test_token");
    }

    private record RecordedRequest(String method, String path, String authorization, String body) {
    }
}
