package com.history.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@DisplayName("PipelineWorkerConfig: pipeline-worker RestClient 설정")
class PipelineWorkerConfigTest {

    @Test
    @DisplayName("connection·read 타임아웃 설정 검증")
    void configuresConnectionAndReadTimeouts() {
        RestClient restClient = new PipelineWorkerConfig()
                .pipelineWorkerRestClient("https://pipeline-worker.test");
        Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");

        assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(3_000);
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(10_000);
    }
}
