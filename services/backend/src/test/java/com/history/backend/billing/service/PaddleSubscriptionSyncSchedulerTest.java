package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import com.history.backend.billing.service.PaddleSubscriptionSyncService.SyncSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("PaddleSubscriptionSyncScheduler: 스케줄러 위임 검증")
class PaddleSubscriptionSyncSchedulerTest {

    @Mock
    private PaddleSubscriptionSyncService syncService;

    @Test
    @DisplayName("스케줄러가 PaddleSubscriptionSyncService.syncAll에 위임한다")
    void runDelegatesToSyncAll() {
        when(syncService.syncAll()).thenReturn(new SyncSummary(1, 2, 0, 0, 0, 0, 0));

        new PaddleSubscriptionSyncScheduler(syncService).run();

        verify(syncService).syncAll();
    }

    @Test
    @DisplayName("서비스가 예외를 던지면 log.error로 남기고 같은 예외를 그대로 재던진다")
    void runLogsErrorAndRethrowsServiceException(CapturedOutput output) {
        RuntimeException failure = new RuntimeException("boom");
        when(syncService.syncAll()).thenThrow(failure);
        PaddleSubscriptionSyncScheduler scheduler = new PaddleSubscriptionSyncScheduler(syncService);

        RuntimeException thrown = assertThrows(RuntimeException.class, scheduler::run);

        assertThat(thrown).isSameAs(failure);
        assertThat(output.getAll()).contains("ERROR");
    }

    @Test
    @DisplayName("@Scheduled는 app.billing.subscription-sync.cron 설정을 쓰는 cron이다")
    void scheduledUsesConfiguredCron() throws NoSuchMethodException {
        Method method = PaddleSubscriptionSyncScheduler.class.getMethod("run");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.billing.subscription-sync.cron}");
        assertThat(scheduled.fixedDelayString()).isEmpty();
        assertThat(scheduled.fixedRateString()).isEmpty();
    }

    @Test
    @DisplayName("app.billing.subscription-sync.enabled=true일 때만 빈으로 등록된다")
    void conditionalOnPropertyRequiresEnabledTrue() {
        ConditionalOnProperty conditional =
                PaddleSubscriptionSyncScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.prefix()).isEqualTo("app.billing.subscription-sync");
        assertThat(conditional.name()).containsExactly("enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("스프링 컴포넌트로 등록된다 — 빠지면 @Scheduled가 있어도 한 번도 실행되지 않는다")
    void isRegisteredAsComponent() {
        assertThat(AnnotatedElementUtils.hasAnnotation(PaddleSubscriptionSyncScheduler.class, Component.class))
                .isTrue();
    }
}
