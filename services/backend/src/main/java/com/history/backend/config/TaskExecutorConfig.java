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

    // Slack Events API 라이프사이클 이벤트 처리 전용 풀.
    // summaryTaskExecutor와 분리해 Slack 이벤트 폭증이 요약 처리를 블로킹하지 않도록 한다.
    // 거부 시 TaskRejectedException → 컨트롤러 5xx → Slack 재시도로 이어진다.
    @Bean("slackEventsTaskExecutor")
    public TaskExecutor slackEventsTaskExecutor(
            @Value("${slack.events-executor-pool-size:1}") int poolSize,
            @Value("${slack.events-executor-queue-capacity:20}") int queueCapacity,
            @Value("${slack.events-executor-await-termination-seconds:30}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("slack-events-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        // 기본 AbortPolicy — 큐가 꽉 차면 TaskRejectedException을 던져 Slack 재시도를 유도한다
        executor.initialize();
        return executor;
    }

    // Slack slash command 단발 질의 전용 풀. events 풀과 분리해 라이프사이클 이벤트와 LLM 질의가 서로를 막지 않게 한다.
    // 질의는 ai-engine read timeout(60s)까지 길 수 있어 await를 그에 맞춘다.
    // 거부 시 AbortPolicy → TaskRejectedException — 커맨드 서비스가 200 + 바쁨 문구로 바꾼다.
    @Bean("slackCommandsTaskExecutor")
    public TaskExecutor slackCommandsTaskExecutor(
            @Value("${slack.commands-executor-pool-size:1}") int poolSize,
            @Value("${slack.commands-executor-queue-capacity:20}") int queueCapacity,
            @Value("${slack.commands-executor-await-termination-seconds:60}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("slack-commands-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
