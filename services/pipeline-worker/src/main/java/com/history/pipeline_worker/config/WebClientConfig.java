package com.history.pipeline_worker.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

// 외부 API(모든 source.*) 호출에 사용하는 HTTP 클라이언트 설정
// WebClient.Builder를 빈으로 등록해두면 각 Service에서 주입받아 재사용 가능
@Configuration
public class WebClientConfig {

    // 응답이 이 시간 안에 오지 않으면 끊는다 — 타임아웃이 없으면 외부 API가 응답을 멈췄을 때
    // Mono.block()이 영원히 파킹되어 초기 수집 스레드 풀(app.collection.executor.pool-size)이
    // 하나씩 잠식된다(실제로 Notion GET /blocks/{id}/children 호출에서 발생 — 재현 시 스레드
    // 덤프에서 CountDownLatch.await()로 무한 대기 확인).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)); // 응답 버퍼 최대 10MB
    }
}
