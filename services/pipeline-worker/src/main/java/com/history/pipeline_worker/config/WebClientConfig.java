package com.history.pipeline_worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// 외부 API(GitHub, Jira, Slack) 호출에 사용하는 HTTP 클라이언트 설정
// WebClient.Builder를 빈으로 등록해두면 각 Service에서 주입받아 재사용 가능
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)); // 응답 버퍼 최대 10MB
    }
}
