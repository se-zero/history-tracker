package com.history.backend.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiEngineConfig {

    // ai-engine hang 시 Tomcat 스레드가 무한 점유되지 않도록 timeout을 건다 — fallback/502가
    // 작동하려면 hang이 예외로 바뀌어야 한다. read는 LLM tool-calling 질의(/query)가 길 수 있어
    // 넉넉히 두고, 빌드(/graph/build)는 비동기 202라 이 timeout 안에서 즉시 반환된다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    RestClient aiEngineRestClient(@Value("${ai.engine.url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
