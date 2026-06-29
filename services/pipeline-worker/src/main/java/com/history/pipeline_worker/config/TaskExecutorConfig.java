package com.history.pipeline_worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TaskExecutorConfig {

    /**
     * webhook 증분 수집 전용 풀.
     * PR 머지 webhook은 증분 수집의 유일 경로이자 백필이 없어, 유실되면 그래프에 영구 빈칸이 남는다.
     * 장기 실행되는 초기 수집(collectionTaskExecutor)과 풀을 분리해 초기 수집이 이 풀을 점유하지 못하게 격리한다.
     * 동시성>1로 여러 프로젝트 webhook을 병렬 처리하되, 동일 프로젝트의 중복 수집은
     * {@code ProjectCollectionSerializer}가 직렬화한다(checkpoint 경합/중복 풀스캔 방지).
     */
    @Bean("webhookTaskExecutor")
    public TaskExecutor webhookTaskExecutor(
            @Value("${app.webhook.executor.pool-size:4}") int poolSize,
            @Value("${app.webhook.executor.await-termination-seconds:300}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("webhook-collector-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    /**
     * 연동 완료 후 trigger되는 초기 수집 전용 풀.
     * 초기 수집은 provider별로 별도 제출되며 PR 단위 diff 조회 등으로 수십 분까지 걸릴 수 있다.
     * provider 슬롯(github/jira/slack checkpoint)이 독립적이고 rate limiter가 무상태라
     * provider 단위 병렬 실행이 안전하므로 기본 풀 크기를 provider 수(3)에 맞춰 초기 수집 지연을 줄인다.
     */
    @Bean("collectionTaskExecutor")
    public TaskExecutor collectionTaskExecutor(
            @Value("${app.collection.executor.pool-size:3}") int poolSize,
            @Value("${app.collection.executor.queue-capacity:100}") int queueCapacity,
            @Value("${app.collection.executor.await-termination-seconds:300}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("initial-collector-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
