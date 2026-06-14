package com.history.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class PipelineWorkerConfigTest {

    @Test
    void configuresConnectionAndReadTimeouts() {
        RestClient restClient = new PipelineWorkerConfig()
                .pipelineWorkerRestClient("https://pipeline-worker.test");
        Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");

        assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(3_000);
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(10_000);
    }
}
