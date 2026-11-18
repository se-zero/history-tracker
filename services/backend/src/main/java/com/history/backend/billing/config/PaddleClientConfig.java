package com.history.backend.billing.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.history.backend.billing.PaddleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaddleClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient paddleRestClient(PaddleProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory())
                .build();
    }

    // Paddle의 예약 변경 삭제는 PATCH인데, HttpURLConnection 기반인 SimpleClientHttpRequestFactory는
    // PATCH를 거절한다(JDK 17에서 ProtocolException: Invalid HTTP method: PATCH). JDK HttpClient로 바꿔 해결한다.
    // HTTP/1.1 고정 이유는 CimdHttpConfig 주석 참고 — JVM 전역 유휴 연결 보관 시간 설정은 HTTP/1.1에만 먹는다.
    static ClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return requestFactory;
    }
}
