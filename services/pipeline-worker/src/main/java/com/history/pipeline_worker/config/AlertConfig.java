package com.history.pipeline_worker.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AlertConfig {

    @Bean
    RestClient alertRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
