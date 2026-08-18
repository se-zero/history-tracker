package com.history.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TaskExecutorConfig {

    // running summary 비동기 갱신 전용 풀.
    // 질의 경로(MessageService.addMessage)가 LLM 요약 완료를 기다리지 않도록 별도 풀로 분리한다.
    // 제출이 거부돼도 다음 턴에 재평가되므로(커서 미전진) queueCapacity는 유한하게 둔다.
    @Bean("summaryTaskExecutor")
    public TaskExecutor summaryTaskExecutor(
            @Value("${conversation.memory.summary-executor-pool-size:1}") int poolSize,
            @Value("${conversation.memory.summary-executor-queue-capacity:50}") int queueCapacity,
            @Value("${conversation.memory.summary-executor-await-termination-seconds:60}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("summary-refresh-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
