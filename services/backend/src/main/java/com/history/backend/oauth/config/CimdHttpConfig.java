package com.history.backend.oauth.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CimdHttpConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    RestClient cimdRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // 리다이렉트를 따라가면 SafeUrlValidator가 검증한 호스트와 실제로 접속하는
                // 호스트가 달라져 SSRF 가드를 우회당한다.
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
