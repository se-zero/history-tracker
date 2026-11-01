package com.history.pipeline_worker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 아웃박스 릴레이·청소 잡용. 레포에 {@code @Scheduled}가 처음이라 여기서 켠다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
