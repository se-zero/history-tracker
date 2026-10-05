package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 실제 소켓으로 총 시간 제한·리다이렉트 비추종을 확인한다 (시간 기반이라 제한 300ms, 판정 2초로 여유를 둔다)
@DisplayName("CimdHttpConfig: CIMD용 RestClient의 총 시간 제한·리다이렉트 비추종")
class CimdHttpConfigTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TOTAL_TIMEOUT = Duration.ofMillis(300);
    private static final Duration SLOW_LIMIT = Duration.ofSeconds(2);

    private HttpServer server;
    private ExecutorService executor;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        // 흘리거나 잠든 핸들러 스레드가 테스트 뒤에 남지 않게 인터럽트한다
        executor.shutdownNow();
    }

    @Test
    @DisplayName("헤더는 즉시, 본문을 3초에 걸쳐 흘리는 응답 → 총 제한(300ms)에서 RestClientException, 흘리기 전체보다 훨씬 빨리 끝남")
    void failsFastWhenBodyIsDrippedPastTotalTimeout() {
        server.createContext("/slow-body", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                OutputStream body = exchange.getResponseBody();
                // 100ms 간격 30번 = 3초 — 한 번 읽을 때의 간격은 총 제한보다 짧아 읽기별 제한으로는 못 끊는다
                for (int i = 0; i < 30; i++) {
                    body.write('x');
                    body.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException ignored) {
                // 클라이언트가 끊었거나 테스트가 끝났다 — 조용히 종료
            } finally {
                exchange.close();
            }
        });
        server.start();
        RestClient restClient = CimdHttpConfig.buildRestClient(CONNECT_TIMEOUT, TOTAL_TIMEOUT);

        long start = System.nanoTime();
        assertThatThrownBy(() -> get(restClient, "/slow-body")).isInstanceOf(RestClientException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(SLOW_LIMIT);
    }

    @Test
    @DisplayName("헤더를 2초 늦게 주는 응답 → 총 제한(300ms)에서 RestClientException, 2초보다 빨리 끝남")
    void failsFastWhenHeadersArriveAfterTotalTimeout() {
        server.createContext("/slow-header", exchange -> {
            try {
                Thread.sleep(2_000);
                respond(exchange, "late");
            } catch (IOException | InterruptedException ignored) {
                // 클라이언트가 끊었거나 테스트가 끝났다 — 조용히 종료
            } finally {
                exchange.close();
            }
        });
        server.start();
        RestClient restClient = CimdHttpConfig.buildRestClient(CONNECT_TIMEOUT, TOTAL_TIMEOUT);

        long start = System.nanoTime();
        assertThatThrownBy(() -> get(restClient, "/slow-header")).isInstanceOf(RestClientException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(SLOW_LIMIT);
    }

    @Test
    @DisplayName("302 응답은 따라가지 않는다 → 상태 코드 302가 그대로 보이고 리다이렉트 대상은 호출되지 않음")
    void doesNotFollowRedirect() throws Exception {
        AtomicBoolean targetCalled = new AtomicBoolean(false);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalled.set(true);
            respond(exchange, "target");
            exchange.close();
        });
        server.start();
        RestClient restClient = CimdHttpConfig.buildRestClient(CONNECT_TIMEOUT, Duration.ofSeconds(5));

        Integer status = restClient.get()
                .uri(baseUrl() + "/redirect")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(302);
        assertThat(targetCalled).isFalse();
    }

    @Test
    @DisplayName("빠른 정상 응답 → 총 제한이 끊지 않고 본문을 그대로 돌려줌")
    void returnsBodyOfFastResponse() {
        server.createContext("/ok", exchange -> {
            respond(exchange, "{\"ok\":true}");
            exchange.close();
        });
        server.start();
        RestClient restClient = CimdHttpConfig.buildRestClient(CONNECT_TIMEOUT, Duration.ofSeconds(5));

        String body = get(restClient, "/ok");

        assertThat(body).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("HTTP/1.1로만 말한다 → HTTP/2 전환 요청 헤더를 보내지 않는다")
    void speaksHttp11Only() {
        AtomicBoolean called = new AtomicBoolean(false);
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> http2Settings = new AtomicReference<>();
        server.createContext("/ok", exchange -> {
            called.set(true);
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            http2Settings.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
            respond(exchange, "{\"ok\":true}");
            exchange.close();
        });
        server.start();
        RestClient restClient = CimdHttpConfig.buildRestClient(CONNECT_TIMEOUT, Duration.ofSeconds(5));

        get(restClient, "/ok");

        // JDK 17의 HTTP/2 연결에는 우리 쪽 유휴 제한이 없어 상대가 닫을 때까지 남는다.
        // HTTP/1.1로 고정해야 유휴 연결 보관 시간 설정이 먹는다.
        assertThat(called).isTrue();
        assertThat(upgrade.get()).isNull();
        assertThat(http2Settings.get()).isNull();
    }

    @Test
    @DisplayName("유휴 연결 보관 시간 — 설정이 없으면 5초로 두고, 이미 준 값은 덮어쓰지 않는다")
    void applyIdleConnectionLimitSetsKeepAliveOnlyWhenUnset() {
        String key = "jdk.httpclient.keepalive.timeout";
        String original = System.getProperty(key);
        try {
            System.clearProperty(key);
            CimdHttpConfig.applyIdleConnectionLimit();
            assertThat(System.getProperty(key)).isEqualTo("5");

            System.setProperty(key, "30");
            CimdHttpConfig.applyIdleConnectionLimit();
            assertThat(System.getProperty(key)).isEqualTo("30");
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    // ── 헬퍼 ──

    // fetcher와 같은 모양 — 본문을 exchange 안에서 읽어야 본문 읽기 도중의 시간 초과가 드러난다
    private String get(RestClient restClient, String path) {
        return restClient.get()
                .uri(baseUrl() + path)
                .exchange((request, response) -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
