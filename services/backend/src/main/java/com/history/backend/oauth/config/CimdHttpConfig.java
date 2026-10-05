package com.history.backend.oauth.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CimdHttpConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);

    private static final String KEEP_ALIVE_PROPERTY = "jdk.httpclient.keepalive.timeout";
    private static final String KEEP_ALIVE_SECONDS = "5";

    @Bean
    RestClient cimdRestClient() {
        return buildRestClient(CONNECT_TIMEOUT, TOTAL_TIMEOUT);
    }

    // JDK HttpClient는 끝난 연결을 재사용하려고 보관하는데, JDK 17 기본값이 1200초·개수 무제한이다.
    // 이 클라이언트는 로그인 없이도 임의의 호스트로 조회를 일으킬 수 있는 경로에 쓰여서, 호스트를 바꿔 가며
    // 요청하면 유휴 연결이 그만큼 쌓인다. 보관 시간을 HttpURLConnection의 기본값과 같은 5초로 줄인다.
    // JVM 전역 설정이고 JDK가 HttpClient를 처음 쓸 때 한 번만 읽으므로, 스프링이 뜨기 전에(main에서) 불러야 한다.
    // 운영자가 실행 옵션으로 준 값이 있으면 건드리지 않는다.
    public static void applyIdleConnectionLimit() {
        if (System.getProperty(KEEP_ALIVE_PROPERTY) == null) {
            System.setProperty(KEEP_ALIVE_PROPERTY, KEEP_ALIVE_SECONDS);
        }
    }

    // 읽기 제한(SimpleClientHttpRequestFactory)은 "한 번 읽을 때" 기준이라 상대가 본문을 조금씩 흘리면
    // 요청 하나가 스레드를 한없이 붙잡는다. JDK 클라이언트의 readTimeout은 요청을 보낸 순간부터
    // 본문 스트림을 닫을 때까지 전체를 재므로 흘리는 응답도 끊는다.
    static RestClient buildRestClient(Duration connectTimeout, Duration totalTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                // JDK 17의 HTTP/2 연결에는 우리 쪽 유휴 제한이 없어 상대가 닫을 때까지 남는다.
                // HTTP/1.1로 고정해야 유휴 연결 보관 시간(applyIdleConnectionLimit)이 먹는다.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                // 리다이렉트를 따라가면 SafeUrlValidator가 검증한 호스트와 실제로 접속하는
                // 호스트가 달라져 SSRF 가드를 우회당한다.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(totalTimeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
