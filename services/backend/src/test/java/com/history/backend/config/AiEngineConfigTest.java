package com.history.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@DisplayName("AiEngineConfig: ai-engine RestClient 설정")
class AiEngineConfigTest {

    @Test
    @DisplayName("connection은 3초, read timeout은 프로퍼티 초 값을 밀리초로 적용한다")
    void appliesConfiguredReadTimeout() {
        RestClient restClient = new AiEngineConfig()
                .aiEngineRestClient("https://ai-engine.test", "token", 120);
        Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");

        assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(3_000);
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(120_000);
    }

    @Test
    @DisplayName("read timeout 초 값을 바꾸면 RestClient에 그대로 반영된다")
    void appliesCustomReadTimeout() {
        RestClient restClient = new AiEngineConfig()
                .aiEngineRestClient("https://ai-engine.test", "token", 90);
        Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");

        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(90_000);
    }
}
