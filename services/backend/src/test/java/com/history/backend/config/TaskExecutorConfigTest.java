package com.history.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("TaskExecutorConfig: 예약 작업 스케줄러 배선")
class TaskExecutorConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(TaskExecutorConfig.class);

    @Test
    @DisplayName("공용 taskScheduler와 전용 스케줄러가 함께 있다 — 전용 빈이 Boot 기본 스케줄러를 꺼 버리지 않는다")
    void keepsDefaultSchedulerAlongsideDedicatedOne() {
        contextRunner.run(context ->
                assertThat(context.getBeansOfType(TaskScheduler.class))
                        .containsOnlyKeys("taskScheduler", "oauthRateLimitEvictionScheduler"));
    }

    @Test
    @DisplayName("타입만으로 조회하면 공용 taskScheduler가 나온다 — @Primary가 없으면 @Scheduled 기본 조회가 모호해진다")
    void defaultSchedulerIsPrimary() {
        contextRunner.run(context ->
                assertThat(context.getBean(TaskScheduler.class)).isSameAs(context.getBean("taskScheduler")));
    }

    @Test
    @DisplayName("IP 기록 청소 전용 스케줄러는 스레드 1개에 oauth-ip-evict- 접두사를 쓴다")
    void evictionSchedulerIsSingleThreadWithDedicatedPrefix() {
        contextRunner.run(context -> {
            ThreadPoolTaskScheduler scheduler =
                    context.getBean("oauthRateLimitEvictionScheduler", ThreadPoolTaskScheduler.class);

            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("oauth-ip-evict-");
            // getPoolSize()는 초기화 뒤엔 살아 있는 스레드 수(첫 작업 전 0)라 설정값은 executor의 corePoolSize로 본다
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        });
    }
}
