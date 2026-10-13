package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("BetaIncrementalReconcileScheduler: 스케줄러 위임 검증")
class BetaIncrementalReconcileSchedulerTest {

    @Mock
    private BetaIncrementalReconcileService service;

    @Test
    @DisplayName("스케줄러가 BetaIncrementalReconcileService.reconcile 위임 호출")
    void reconcileDelegatesToService() {
        when(service.reconcile()).thenReturn(3);

        new BetaIncrementalReconcileScheduler(service).reconcile();

        verify(service).reconcile();
    }

    @Test
    @DisplayName("서비스가 예외를 던지면 그대로 재던진다")
    void reconcileRethrowsServiceException() {
        when(service.reconcile()).thenThrow(new RuntimeException("boom"));

        BetaIncrementalReconcileScheduler scheduler = new BetaIncrementalReconcileScheduler(service);

        assertThrows(RuntimeException.class, scheduler::reconcile);
    }

    // cron으로 바꾸면 "서버 시작 직후 한 번 실행"이 사라져, 베타 종료 뒤 재기동한 서버가 다음 정각까지
    // 증분 수집 플래그를 방치한다. 그 회귀를 어노테이션 값으로 막는다.
    @Test
    @DisplayName("@Scheduled는 시작 직후 실행(initialDelay=0) + 설정 주기 fixedDelay이고 cron이 아니다")
    void scheduledRunsAtStartupThenAtFixedDelay() throws NoSuchMethodException {
        Method method = BetaIncrementalReconcileScheduler.class.getMethod("reconcile");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelay()).isZero();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${app.plan.beta-reconcile.interval}");
        assertThat(scheduled.cron()).isEmpty();
    }

    @Test
    @DisplayName("app.plan.beta-reconcile.enabled=true일 때만 빈으로 등록된다")
    void conditionalOnPropertyRequiresEnabledTrue() {
        ConditionalOnProperty conditional =
                BetaIncrementalReconcileScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.prefix()).isEqualTo("app.plan.beta-reconcile");
        assertThat(conditional.name()).containsExactly("enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
    }
}
